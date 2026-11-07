package io.github.baokhang83.mnemo.warden.agent.boost;

import io.github.baokhang83.mnemo.warden.agent.resize.InClusterApiServer;
import io.github.baokhang83.mnemo.warden.agent.resize.MinimalJson;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD against {@code WardenBoost} over the same in-cluster API server connection {@code
 * PodIntentReader}/{@code PodResizeClient} use, via {@link InClusterApiServer}. B8 reuses the
 * agent's existing identity (the {@code warden-boost-agent} ServiceAccount) rather than a second
 * one &mdash; a projected service-account token is always minted for the Pod's own {@code
 * spec.serviceAccountName}, so a Pod cannot run as two ServiceAccounts at once. The chart grants
 * this identity {@code get/list/watch/create/patch} on {@code wardenboosts} (never {@code
 * delete}, never the {@code status} subresource) and the {@code boost-request-guard} admission
 * policy narrows what it may do with that RBAC to self-pod-bound requests only.
 */
public final class BoostRequestClient implements BoostRequestPort {

  private static final String API_GROUP_PATH = "/apis/warden.mnemo.io/v1alpha1/namespaces/";

  private final InClusterApiServer apiServer;
  private final HttpClient httpClient;

  /** Builds a client using the pod's own in-cluster credentials. */
  public static BoostRequestClient forInClusterAgent() throws IOException, GeneralSecurityException {
    InClusterApiServer apiServer = InClusterApiServer.discover();
    HttpClient httpClient = HttpClient.newBuilder().sslContext(apiServer.sslContext()).build();
    return new BoostRequestClient(apiServer, httpClient);
  }

  BoostRequestClient(InClusterApiServer apiServer, HttpClient httpClient) {
    this.apiServer = apiServer;
    this.httpClient = httpClient;
  }

  /** The named boost, or empty if it doesn't exist (a {@code 404}). */
  @Override
  public Optional<BoostSnapshot> get(String name) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(boostUri(name))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + apiServer.bearerToken())
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw new IOException("get boost " + name + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return Optional.of(parseSnapshot(response.body()));
  }

  /**
   * Creates the named boost if it doesn't already exist, otherwise returns the existing one
   * unchanged &mdash; the idempotency "stable ID" acceptance criterion in one call: a caller
   * retrying {@code acquire} with the same {@code requestId} never double-creates.
   */
  @Override
  public BoostSnapshot getOrCreate(
      String name, String policyRef, String podName, String podUid, String container, String boost)
      throws IOException, InterruptedException {
    Optional<BoostSnapshot> existing = get(name);
    if (existing.isPresent()) {
      return existing.get();
    }
    String body =
        "{\"apiVersion\":\"warden.mnemo.io/v1alpha1\",\"kind\":\"WardenBoost\","
            + "\"metadata\":{\"name\":" + JsonString.quote(name) + "},"
            + "\"spec\":{\"policyRef\":" + JsonString.quote(policyRef)
            + ",\"podRef\":{\"name\":" + JsonString.quote(podName) + ",\"uid\":" + JsonString.quote(podUid) + "}"
            + ",\"container\":" + JsonString.quote(container)
            + ",\"boost\":" + JsonString.quote(boost)
            + ",\"completionRequested\":false}}";
    HttpRequest request =
        HttpRequest.newBuilder(collectionUri())
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + apiServer.bearerToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 201) {
      return parseSnapshot(response.body());
    }
    if (response.statusCode() == 409) {
      // Lost a create race against a retried/concurrent caller using the same requestId.
      return get(name).orElseThrow(() -> new IOException("boost " + name + " vanished after a create conflict"));
    }
    throw new IOException("create boost " + name + " failed: HTTP " + response.statusCode() + " " + response.body());
  }

  /**
   * Requests completion (idempotent: the CRD's own validation rule allows true&rarr;true as a
   * no-op, only true&rarr;false is forbidden). Retries once against a fresh {@code
   * resourceVersion} if the precondition lost a race.
   */
  @Override
  public BoostSnapshot requestCompletion(String name, String resourceVersion) throws IOException, InterruptedException {
    String rv = resourceVersion;
    for (int attempt = 0; attempt < 2; attempt++) {
      String body =
          "[{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":" + JsonString.quote(rv)
              + "},{\"op\":\"add\",\"path\":\"/spec/completionRequested\",\"value\":true}]";
      HttpRequest request =
          HttpRequest.newBuilder(boostUri(name))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + apiServer.bearerToken())
              .header("Content-Type", "application/json-patch+json")
              .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return parseSnapshot(response.body());
      }
      if (response.statusCode() == 409 && attempt == 0) {
        rv = get(name).orElseThrow(() -> new IOException("boost " + name + " no longer exists")).resourceVersion();
        continue;
      }
      throw new IOException("release " + name + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    throw new IOException("release " + name + " failed: resourceVersion kept changing");
  }

  @SuppressWarnings("unchecked")
  static BoostSnapshot parseSnapshot(String json) {
    Map<String, Object> boost = (Map<String, Object>) MinimalJson.parse(json);
    Map<String, Object> metadata = (Map<String, Object>) boost.get("metadata");
    String name = (String) metadata.get("name");
    String resourceVersion = (String) metadata.get("resourceVersion");
    Map<String, Object> status = (Map<String, Object>) boost.getOrDefault("status", Map.of());
    String phase = (String) status.getOrDefault("phase", "Pending");
    String message = null;
    List<Object> conditions = (List<Object>) status.get("conditions");
    if (conditions != null && !conditions.isEmpty()) {
      Map<String, Object> last = (Map<String, Object>) conditions.get(conditions.size() - 1);
      message = (String) last.get("message");
    }
    return new BoostSnapshot(name, resourceVersion, phase, message);
  }

  private URI collectionUri() {
    return apiServer.baseUri().resolve(API_GROUP_PATH + apiServer.namespace() + "/wardenboosts");
  }

  private URI boostUri(String name) {
    return apiServer.baseUri().resolve(API_GROUP_PATH + apiServer.namespace() + "/wardenboosts/" + name);
  }
}
