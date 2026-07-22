package io.github.baokhang83.mnemo.warden.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthServerTest {

  private HealthState health;
  private AgentMetrics metrics;
  private HealthServer server;
  private HttpClient client;

  @BeforeEach
  void startServer() throws Exception {
    health = new HealthState();
    metrics = new AgentMetrics();
    server = new HealthServer(0, health, metrics); // ephemeral port
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private int statusOf(String path) throws Exception {
    return get(path).statusCode();
  }

  @Test
  void healthzIsAlways200() throws Exception {
    assertEquals(200, statusOf("/healthz"));
  }

  @Test
  void readyzTracksReadiness() throws Exception {
    assertEquals(503, statusOf("/readyz"), "not ready until marked");
    health.markReady();
    assertEquals(200, statusOf("/readyz"));
    health.markNotReady();
    assertEquals(503, statusOf("/readyz"), "readiness can be withdrawn (e.g. on shutdown)");
  }

  @Test
  void metricsServesTheRegistrysCurrentRender() throws Exception {
    metrics.incrementResize("grow");

    HttpResponse<String> response = get("/metrics");

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("warden_resizes_total{direction=\"grow\"} 1"));
  }
}
