package io.github.baokhang83.mnemo.warden.agent.resize;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Changes a running pod's container memory request/limit without a restart, via the Kubernetes
 * 1.35 (GA) {@code /resize} subresource &mdash; verified end-to-end against a real kind cluster
 * (K8s 1.36).
 *
 * <p>Only ever deals in memory: confirmed a memory-only patch body leaves the container's CPU
 * request/limit untouched, and Warden is a memory-efficiency product with no CPU-resize
 * acceptance criterion anywhere in the roadmap.
 *
 * <p>Uses {@code Content-Type: application/strategic-merge-patch+json}, not {@code
 * application/merge-patch+json}: confirmed the latter wholesale-replaces the {@code containers}
 * array (JSON Merge Patch's array semantics) and gets rejected by the API server as {@code only
 * cpu and memory resources are mutable}, since every container field not repeated in the patch
 * body reads as "removed."
 *
 * <p>A successful PATCH response is not proof the kubelet applied the resize &mdash; {@code
 * spec.containers[].resources} updates immediately, but {@code status.containerStatuses[]}
 * (what's actually configured) lags behind. {@link #resizeMemory} polls status until it matches,
 * comparing byte counts via {@link K8sQuantity} rather than strings, since the API server
 * normalizes the representation (confirmed: a byte count PATCHed as {@code "209715200"} comes
 * back in status as {@code "200Mi"} &mdash; a different string for the same value).
 */
public final class PodResizeClient {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  private final InClusterApiServer apiServer;
  private final HttpClient httpClient;

  PodResizeClient(InClusterApiServer apiServer, HttpClient httpClient) {
    this.apiServer = apiServer;
    this.httpClient = httpClient;
  }

  /** Builds a client from the pod's own in-cluster credentials. */
  public static PodResizeClient forInClusterAgent() throws IOException, GeneralSecurityException {
    InClusterApiServer apiServer = InClusterApiServer.discover();
    HttpClient httpClient = HttpClient.newBuilder().sslContext(apiServer.sslContext()).build();
    return new PodResizeClient(apiServer, httpClient);
  }

  /**
   * PATCHes {@code containerName}'s memory request/limit and blocks until the kubelet has
   * confirmed it in {@code status}, or {@code timeout} elapses.
   *
   * @throws ResizeTimeoutException if the kubelet hadn't applied it by the deadline
   */
  public void resizeMemory(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout)
      throws IOException, InterruptedException {
    patch(podName, containerName, requestBytes, limitBytes);
    awaitConfirmation(podName, containerName, requestBytes, limitBytes, timeout);
  }

  private void patch(String podName, String containerName, long requestBytes, long limitBytes)
      throws IOException, InterruptedException {
    HttpRequest request =
        authorizedRequestBuilder(resizeUri(podName))
            .header("Content-Type", "application/strategic-merge-patch+json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody(containerName, requestBytes, limitBytes)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("resize PATCH for " + podName + "/" + containerName + " failed: HTTP "
          + response.statusCode() + " " + response.body());
    }
  }

  /**
   * K8s container names are validated by the API server against RFC 1123 label rules (lowercase
   * alphanumeric and hyphens) &mdash; they can never contain a quote or backslash, so this string
   * concatenation needs no JSON escaping. Memory values are plain decimal byte counts (verified
   * accepted with no unit suffix), so callers of {@link PodResizeClient} never format a quantity.
   */
  static String patchBody(String containerName, long requestBytes, long limitBytes) {
    return "{\"spec\":{\"containers\":[{\"name\":\"" + containerName + "\",\"resources\":{"
        + "\"requests\":{\"memory\":\"" + requestBytes + "\"},"
        + "\"limits\":{\"memory\":\"" + limitBytes + "\"}}}]}}";
  }

  private void awaitConfirmation(
      String podName, String containerName, long requestBytes, long limitBytes, Duration timeout)
      throws IOException, InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (matchesDesired(podName, containerName, requestBytes, limitBytes)) {
        return;
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
    if (!matchesDesired(podName, containerName, requestBytes, limitBytes)) {
      throw new ResizeTimeoutException(podName, containerName);
    }
  }

  private boolean matchesDesired(String podName, String containerName, long requestBytes, long limitBytes)
      throws IOException, InterruptedException {
    String body = getPod(podName);
    long[] observed = extractConfirmedMemory(body, containerName);
    return observed != null && observed[0] == requestBytes && observed[1] == limitBytes;
  }

  /**
   * Reads {@code status.containerStatuses[containerName].resources.requests/limits.memory},
   * returning {@code {requestBytes, limitBytes}}, or {@code null} if the container has no status
   * yet (e.g. immediately after pod creation).
   */
  @SuppressWarnings("unchecked")
  static long[] extractConfirmedMemory(String getPodResponseJson, String containerName) {
    Map<String, Object> pod = (Map<String, Object>) MinimalJson.parse(getPodResponseJson);
    Map<String, Object> status = (Map<String, Object>) pod.get("status");
    if (status == null) {
      return null;
    }
    List<Object> containerStatuses = (List<Object>) status.get("containerStatuses");
    if (containerStatuses == null) {
      return null;
    }

    for (Object entry : containerStatuses) {
      Map<String, Object> containerStatus = (Map<String, Object>) entry;
      if (!containerName.equals(containerStatus.get("name"))) {
        continue;
      }
      Map<String, Object> resources = (Map<String, Object>) containerStatus.get("resources");
      if (resources == null) {
        return null;
      }
      Map<String, Object> requests = (Map<String, Object>) resources.get("requests");
      Map<String, Object> limits = (Map<String, Object>) resources.get("limits");
      if (requests == null || limits == null || requests.get("memory") == null || limits.get("memory") == null) {
        return null;
      }
      return new long[] {
        K8sQuantity.parseBytes((String) requests.get("memory")), K8sQuantity.parseBytes((String) limits.get("memory"))
      };
    }
    return null;
  }

  private String getPod(String podName) throws IOException, InterruptedException {
    HttpRequest request = authorizedRequestBuilder(podUri(podName)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("GET pod " + podName + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return response.body();
  }

  private HttpRequest.Builder authorizedRequestBuilder(URI uri) throws IOException {
    return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + apiServer.bearerToken());
  }

  private URI podUri(String podName) {
    return apiServer.baseUri().resolve("/api/v1/namespaces/" + apiServer.namespace() + "/pods/" + podName);
  }

  private URI resizeUri(String podName) {
    return apiServer.baseUri().resolve("/api/v1/namespaces/" + apiServer.namespace() + "/pods/" + podName + "/resize");
  }
}
