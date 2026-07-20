package io.github.baokhang83.mnemo.warden.agent.resize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a throwaway self-signed certificate (PEM) for tests, via the JDK's own {@code
 * keytool} &mdash; there is no public JDK API for certificate generation, and pulling in a crypto
 * library just to test {@link InClusterApiServer}'s trust-store wiring would be a heavier
 * dependency than the thing being tested.
 */
final class SelfSignedCert {

  private SelfSignedCert() {}

  static byte[] generatePem() throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory("self-signed-cert");
    Path keystore = dir.resolve("keystore.p12");
    Path certOut = dir.resolve("cert.pem");

    run(
        "keytool", "-genkeypair",
        "-alias", "test",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "1",
        "-storetype", "PKCS12",
        "-keystore", keystore.toString(),
        "-storepass", "changeit",
        "-dname", "CN=test-ca");
    run(
        "keytool", "-exportcert",
        "-alias", "test",
        "-keystore", keystore.toString(),
        "-storepass", "changeit",
        "-rfc",
        "-file", certOut.toString());

    return Files.readAllBytes(certOut);
  }

  private static void run(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("keytool command failed (exit " + exit + "): " + String.join(" ", command));
    }
  }
}
