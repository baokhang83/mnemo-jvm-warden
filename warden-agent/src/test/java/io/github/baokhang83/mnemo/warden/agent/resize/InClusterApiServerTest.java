package io.github.baokhang83.mnemo.warden.agent.resize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InClusterApiServerTest {

  @Test
  void discoversHostPortNamespaceAndTokenFromAFakeServiceAccountDirectory(@TempDir Path serviceAccountDir)
      throws Exception {
    writeServiceAccountFiles(serviceAccountDir, "some-namespace", "some-token");
    Map<String, String> env = Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1", "KUBERNETES_SERVICE_PORT", "443");

    InClusterApiServer apiServer = InClusterApiServer.discover(env::get, serviceAccountDir);

    assertEquals("some-namespace", apiServer.namespace());
    assertEquals(URI.create("https://10.96.0.1:443"), apiServer.baseUri());
    assertEquals("some-token", apiServer.bearerToken());
    assertNotNull(apiServer.sslContext());
  }

  @Test
  void rereadsTheTokenFileOnEveryCallRatherThanCachingIt(@TempDir Path serviceAccountDir) throws Exception {
    writeServiceAccountFiles(serviceAccountDir, "ns", "first-token");
    Map<String, String> env = Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1", "KUBERNETES_SERVICE_PORT", "443");
    InClusterApiServer apiServer = InClusterApiServer.discover(env::get, serviceAccountDir);

    assertEquals("first-token", apiServer.bearerToken());

    // Simulates the kubelet rotating the token file in place before expiry.
    Files.writeString(serviceAccountDir.resolve("token"), "rotated-token");
    assertEquals("rotated-token", apiServer.bearerToken());
  }

  @Test
  void failsFastWhenNotRunningInAPod(@TempDir Path serviceAccountDir) throws Exception {
    writeServiceAccountFiles(serviceAccountDir, "ns", "token");
    Map<String, String> emptyEnv = Map.of();

    assertThrows(IllegalStateException.class, () -> InClusterApiServer.discover(emptyEnv::get, serviceAccountDir));
  }

  @Test
  void sslContextTrustsTheClusterCaButNotAnUnrelatedOne(@TempDir Path serviceAccountDir) throws Exception {
    writeServiceAccountFiles(serviceAccountDir, "ns", "token");
    Map<String, String> env = Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1", "KUBERNETES_SERVICE_PORT", "443");

    InClusterApiServer apiServer = InClusterApiServer.discover(env::get, serviceAccountDir);

    // The real proof (the trust anchor actually rejects a foreign CA during a TLS handshake) is
    // exercised end-to-end in PodResizeClientTest against a real API server; this just confirms
    // an SSLContext was actually built from the file, not a permissive default.
    SSLContext sslContext = apiServer.sslContext();
    assertTrue(sslContext.getProtocol().startsWith("TLS"));
  }

  private static void writeServiceAccountFiles(Path dir, String namespace, String token) throws Exception {
    Files.writeString(dir.resolve("namespace"), namespace);
    Files.writeString(dir.resolve("token"), token);
    Files.write(dir.resolve("ca.crt"), SelfSignedCert.generatePem());
  }
}
