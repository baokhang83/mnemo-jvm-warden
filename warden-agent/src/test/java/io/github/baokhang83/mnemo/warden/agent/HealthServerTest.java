package io.github.baokhang83.mnemo.warden.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthServerTest {

  private HealthState health;
  private HealthServer server;
  private HttpClient client;

  @BeforeEach
  void startServer() throws Exception {
    health = new HealthState();
    server = new HealthServer(0, health); // ephemeral port
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  private int statusOf(String path) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + path))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
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
}
