package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.agent.resize.InClusterApiServer;
import io.github.baokhang83.mnemo.warden.agent.resize.K8sQuantity;
import io.github.baokhang83.mnemo.warden.agent.resize.MinimalJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the agent's own pod &mdash; its W-306 intent annotations (target request/limit bytes)
 * and the target container's <em>actual current</em> memory limit &mdash; over the same
 * in-cluster API server connection {@code PodResizeClient} uses, via {@link InClusterApiServer}
 * and the shared {@link MinimalJson} reader.
 *
 * <p>Reading the actual current limit (not tracking a separately-cached "last applied" value)
 * is deliberate: once a resize succeeds, the actual limit matches the intent, so the next poll's
 * comparison naturally becomes a no-op. No extra state to keep in sync, and a failed/timed-out
 * resize is retried automatically on the next tick.
 */
public final class PodIntentReader {

  static final String ANNOTATION_REQUEST_BYTES = "warden.mnemo.io/target-request-bytes";
  static final String ANNOTATION_LIMIT_BYTES = "warden.mnemo.io/target-limit-bytes";

  private final InClusterApiServer apiServer;
  private final HttpClient httpClient;
  private final String podName;
  private final String containerName;

  /** Builds a reader using the pod's own in-cluster credentials. */
  public static PodIntentReader forInClusterAgent(String podName, String containerName)
      throws IOException, GeneralSecurityException {
    InClusterApiServer apiServer = InClusterApiServer.discover();
    HttpClient httpClient = HttpClient.newBuilder().sslContext(apiServer.sslContext()).build();
    return new PodIntentReader(apiServer, httpClient, podName, containerName);
  }

  PodIntentReader(InClusterApiServer apiServer, HttpClient httpClient, String podName, String containerName) {
    this.apiServer = apiServer;
    this.httpClient = httpClient;
    this.podName = podName;
    this.containerName = containerName;
  }

  /** The current intent (if any annotation is present) and the container's actual live limit. */
  public PodState read() throws IOException, InterruptedException {
    String body = getPod();
    return new PodState(extractIntent(body), extractCurrentLimitBytes(body, containerName));
  }

  /**
   * Package-private and static, taking the raw JSON string directly &mdash; mirrors {@code
   * PodResizeClient.extractConfirmedMemory}'s existing pattern, so tests exercise the real
   * parsing logic without a live HTTP round trip.
   */
  @SuppressWarnings("unchecked")
  static Optional<Intent> extractIntent(String getPodResponseJson) {
    Map<String, Object> pod = (Map<String, Object>) MinimalJson.parse(getPodResponseJson);
    Map<String, Object> metadata = (Map<String, Object>) pod.get("metadata");
    if (metadata == null) {
      return Optional.empty();
    }
    Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
    if (annotations == null) {
      return Optional.empty();
    }
    String requestRaw = (String) annotations.get(ANNOTATION_REQUEST_BYTES);
    String limitRaw = (String) annotations.get(ANNOTATION_LIMIT_BYTES);
    if (requestRaw == null || limitRaw == null) {
      return Optional.empty();
    }
    return Optional.of(new Intent(Long.parseLong(requestRaw), Long.parseLong(limitRaw)));
  }

  @SuppressWarnings("unchecked")
  static OptionalLong extractCurrentLimitBytes(String getPodResponseJson, String containerName) {
    Map<String, Object> pod = (Map<String, Object>) MinimalJson.parse(getPodResponseJson);
    Map<String, Object> status = (Map<String, Object>) pod.get("status");
    if (status == null) {
      return OptionalLong.empty();
    }
    List<Object> containerStatuses = (List<Object>) status.get("containerStatuses");
    if (containerStatuses == null) {
      return OptionalLong.empty();
    }
    for (Object entry : containerStatuses) {
      Map<String, Object> containerStatus = (Map<String, Object>) entry;
      if (!containerName.equals(containerStatus.get("name"))) {
        continue;
      }
      Map<String, Object> resources = (Map<String, Object>) containerStatus.get("resources");
      if (resources == null) {
        return OptionalLong.empty();
      }
      Map<String, Object> limits = (Map<String, Object>) resources.get("limits");
      if (limits == null || limits.get("memory") == null) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(K8sQuantity.parseBytes((String) limits.get("memory")));
    }
    return OptionalLong.empty();
  }

  private String getPod() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(podUri())
            .header("Authorization", "Bearer " + apiServer.bearerToken())
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("GET pod " + podName + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return response.body();
  }

  private URI podUri() {
    return apiServer.baseUri().resolve("/api/v1/namespaces/" + apiServer.namespace() + "/pods/" + podName);
  }
}
