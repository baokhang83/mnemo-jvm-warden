package io.github.baokhang83.mnemo.warden.boost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WardenBoostClientTest {

  private static final String TOKEN = "test-token";

  private FakeBoostApiServer server;
  private WardenBoostClient client;
  private Path tokenFile;

  @BeforeEach
  void startServer(@TempDir Path dir) throws Exception {
    server = new FakeBoostApiServer(TOKEN);
    server.start();
    tokenFile = dir.resolve("token");
    Files.writeString(tokenFile, TOKEN);
    client = new WardenBoostClient(server.baseUri(), tokenFile, HttpClient.newHttpClient(), Duration.ofMillis(20));
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void acquireGrantsImmediatelyWhenTheServerGrantsRightAway() throws Exception {
    try (BoostLease lease = client.acquireBoost("index-rebuild", Duration.ofSeconds(5))) {
      assertTrue(lease.requestId() != null && !lease.requestId().isBlank());
    }
    assertEquals(1, server.releaseCallCount.get());
  }

  @Test
  void acquireBlocksThroughPendingUntilGranted() throws Exception {
    String requestId = "resumable-1";
    server.script(requestId, "Pending", "Pending", "Preparing", "Granted");

    try (BoostLease lease = client.acquireBoost("index-rebuild", requestId, Duration.ofSeconds(5))) {
      assertEquals(requestId, lease.requestId());
    }
  }

  @Test
  void acquireThrowsRejectedWhenTheServerDeniesTheRequest() {
    String requestId = "rejected-1";
    server.script(requestId, "Pending", "Rejected");

    BoostRejectedException e = assertThrows(BoostRejectedException.class,
        () -> client.acquireBoost("index-rebuild", requestId, Duration.ofSeconds(5)));
    assertEquals(requestId, e.requestId());
    assertEquals("Rejected", e.phase());
  }

  @Test
  void acquireTimesOutWithoutReleasingAnUncertainGrant() {
    String requestId = "slow-1";
    server.script(requestId, "Pending"); // never settles

    BoostAcquisitionTimeoutException e = assertThrows(BoostAcquisitionTimeoutException.class,
        () -> client.acquireBoost("index-rebuild", requestId, Duration.ofMillis(100)));
    assertEquals(requestId, e.requestId());
    assertEquals(0, server.releaseCallCount.get(), "a timeout must never auto-release the uncertain request");
  }

  @Test
  void acquireCanResumeTheSameRequestIdAfterATimeout() throws Exception {
    String requestId = "retry-1";
    server.script(requestId, "Pending", "Pending", "Granted");

    assertThrows(BoostAcquisitionTimeoutException.class,
        () -> client.acquireBoost("index-rebuild", requestId, Duration.ofMillis(30)));

    try (BoostLease lease = client.acquireBoost("index-rebuild", requestId, Duration.ofSeconds(5))) {
      assertEquals(requestId, lease.requestId());
    }
  }

  @Test
  void acquireRetriesTransientServerErrorsWithinTheTimeoutBudget() throws Exception {
    String requestId = "flaky-1";
    server.script(requestId, "503", "502", "Granted");

    try (BoostLease lease = client.acquireBoost("index-rebuild", requestId, Duration.ofSeconds(5))) {
      assertEquals(requestId, lease.requestId());
    }
  }

  @Test
  void acquireFailsFastOnAWrongBearerToken() throws Exception {
    Files.writeString(tokenFile, "wrong-token");

    assertThrows(Exception.class, () -> client.acquireBoost("index-rebuild", Duration.ofSeconds(5)));
  }

  @Test
  void closeIsIdempotent() throws Exception {
    BoostLease lease = client.acquireBoost("index-rebuild", Duration.ofSeconds(5));
    lease.close();
    lease.close();
    assertEquals(2, server.releaseCallCount.get());
  }

  @Test
  void statusReFetchesAfterClose() throws Exception {
    String requestId = "status-1";
    server.script(requestId, "Granted", "Granted", "Completed");

    try (BoostLease lease = client.acquireBoost("index-rebuild", requestId, Duration.ofSeconds(5))) {
      lease.status(); // "Granted" again
    }
    BoostStatus after = client.inspect(requestId);
    assertEquals("Completed", after.phase());
  }

  @Test
  void acquireRejectsABlankBoostName() {
    assertThrows(IllegalArgumentException.class,
        () -> client.acquireBoost("", Duration.ofSeconds(1)));
  }

  @Test
  void discoverUsesAgentDefaultsWhenEnvironmentIsEmpty() {
    WardenBoostClient discovered = WardenBoostClient.discover(Map.<String, String>of()::get);
    assertEquals(java.net.URI.create("http://127.0.0.1:8090"), discovered.baseUri());
    assertEquals(Path.of("/var/run/warden/boost/token"), discovered.tokenPath());
  }

  @Test
  void discoverHonorsOverriddenPortAndTokenPath() {
    Map<String, String> env = Map.of("WARDEN_BOOST_PORT", "9999", "WARDEN_BOOST_TOKEN_PATH", "/tmp/custom-token");
    WardenBoostClient discovered = WardenBoostClient.discover(env::get);
    assertEquals(java.net.URI.create("http://127.0.0.1:9999"), discovered.baseUri());
    assertEquals(Path.of("/tmp/custom-token"), discovered.tokenPath());
  }

  @Test
  void discoverRejectsANonNumericPort() {
    assertThrows(IllegalArgumentException.class,
        () -> WardenBoostClient.discover(Map.of("WARDEN_BOOST_PORT", "abc")::get));
  }
}
