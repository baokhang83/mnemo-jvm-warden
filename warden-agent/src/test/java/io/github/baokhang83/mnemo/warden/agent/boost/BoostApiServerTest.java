package io.github.baokhang83.mnemo.warden.agent.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the HTTP surface end to end against an in-memory fake {@link BoostRequestPort}
 * (no real Kubernetes API server), mirroring {@code HealthServerTest}'s ephemeral-port pattern.
 */
class BoostApiServerTest {

  private FakeBoostRequestPort client;
  private BoostApiServer server;
  private HttpClient httpClient;
  private static final String TOKEN = "test-token";

  @BeforeEach
  void startServer() throws Exception {
    client = new FakeBoostRequestPort();
    server =
        new BoostApiServer(
            0, TOKEN, client, "my-policy", "my-pod", "pod-uid-1", "app", Duration.ofSeconds(2));
    server.start();
    httpClient = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void rejectsAcquireWithoutABearerToken() throws Exception {
    HttpResponse<String> response = post("/v1/boost/acquire", "{\"boost\":\"index-rebuild\"}", null);
    assertEquals(401, response.statusCode());
  }

  @Test
  void rejectsAcquireWithTheWrongBearerToken() throws Exception {
    HttpResponse<String> response = post("/v1/boost/acquire", "{\"boost\":\"index-rebuild\"}", "wrong-token");
    assertEquals(401, response.statusCode());
  }

  @Test
  void acquireGrantsImmediatelyWhenTheFakeControllerGrantsRightAway() throws Exception {
    client.phaseOnCreate = "Granted";

    HttpResponse<String> response = post("/v1/boost/acquire", "{\"boost\":\"index-rebuild\"}", TOKEN);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"phase\":\"Granted\""));
    assertTrue(response.body().contains("\"requestId\""));
  }

  @Test
  void acquireReturns202WhenTheBoundedWaitElapsesBeforeGranting() throws Exception {
    client.phaseOnCreate = "Pending"; // never settles
    server.stop();
    server = new BoostApiServer(0, TOKEN, client, "my-policy", "my-pod", "pod-uid-1", "app", Duration.ZERO);
    server.start();

    HttpResponse<String> response = post("/v1/boost/acquire", "{\"boost\":\"index-rebuild\"}", TOKEN);

    assertEquals(202, response.statusCode());
    assertTrue(response.body().contains("\"phase\":\"Pending\""));
  }

  @Test
  void acquireRejectsAMissingBoostName() throws Exception {
    HttpResponse<String> response = post("/v1/boost/acquire", "{}", TOKEN);
    assertEquals(400, response.statusCode());
  }

  @Test
  void acquireIsIdempotentOnTheSameRequestId() throws Exception {
    client.phaseOnCreate = "Granted";

    HttpResponse<String> first = post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-1\"}", TOKEN);
    HttpResponse<String> second = post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-1\"}", TOKEN);

    assertEquals(200, first.statusCode());
    assertEquals(200, second.statusCode());
    assertEquals(1, client.createCalls, "retrying the same requestId must not create a second boost");
  }

  @Test
  void acquireRejectsASecondConcurrentRequestWithADifferentId() throws Exception {
    client.phaseOnCreate = "Pending"; // stays outstanding (not terminal) for the whole bounded wait
    server.stop();
    server = new BoostApiServer(0, TOKEN, client, "my-policy", "my-pod", "pod-uid-1", "app", Duration.ZERO);
    server.start();

    HttpResponse<String> first = post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-1\"}", TOKEN);
    HttpResponse<String> second = post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-2\"}", TOKEN);

    assertEquals(202, first.statusCode());
    assertEquals(409, second.statusCode());
    assertTrue(second.body().contains("req-1"), "the conflict response names the already-outstanding request");
  }

  @Test
  void statusReportsTheCurrentPhase() throws Exception {
    client.phaseOnCreate = "Granted";
    post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-1\"}", TOKEN);
    client.snapshots.get("req-1").phase = "Completed";

    HttpResponse<String> response = get("/v1/boost/status?requestId=req-1", TOKEN);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"phase\":\"Completed\""));
  }

  @Test
  void statusIs404ForAnUnknownRequestId() throws Exception {
    HttpResponse<String> response = get("/v1/boost/status?requestId=nope", TOKEN);
    assertEquals(404, response.statusCode());
  }

  @Test
  void releaseRequestsCompletion() throws Exception {
    client.phaseOnCreate = "Granted";
    post("/v1/boost/acquire", "{\"boost\":\"x\",\"requestId\":\"req-1\"}", TOKEN);

    HttpResponse<String> response = post("/v1/boost/release", "{\"requestId\":\"req-1\"}", TOKEN);

    assertEquals(200, response.statusCode());
    assertTrue(client.completionRequested.contains("req-1"));
  }

  @Test
  void releaseIs404ForAnUnknownRequestId() throws Exception {
    HttpResponse<String> response = post("/v1/boost/release", "{\"requestId\":\"nope\"}", TOKEN);
    assertEquals(404, response.statusCode());
  }

  private HttpResponse<String> post(String path, String body, String token) throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** In-memory stand-in for the real Kubernetes-backed {@link BoostRequestPort}. */
  private static final class FakeBoostRequestPort implements BoostRequestPort {
    String phaseOnCreate = "Pending";
    int createCalls;
    final Map<String, MutableSnapshot> snapshots = new LinkedHashMap<>();
    final java.util.Set<String> completionRequested = new java.util.HashSet<>();

    @Override
    public Optional<BoostSnapshot> get(String name) {
      MutableSnapshot snapshot = snapshots.get(name);
      return snapshot == null ? Optional.empty() : Optional.of(snapshot.toSnapshot());
    }

    @Override
    public BoostSnapshot getOrCreate(
        String name, String policyRef, String podName, String podUid, String container, String boost) {
      MutableSnapshot existing = snapshots.get(name);
      if (existing != null) {
        return existing.toSnapshot();
      }
      createCalls++;
      MutableSnapshot created = new MutableSnapshot();
      created.name = name;
      created.phase = phaseOnCreate;
      snapshots.put(name, created);
      return created.toSnapshot();
    }

    @Override
    public BoostSnapshot requestCompletion(String name, String resourceVersion) {
      completionRequested.add(name);
      MutableSnapshot snapshot = snapshots.get(name);
      return snapshot.toSnapshot();
    }

    private static final class MutableSnapshot {
      String name;
      String phase;
      int version;

      BoostSnapshot toSnapshot() {
        return new BoostSnapshot(name, String.valueOf(version), phase, null);
      }
    }
  }
}
