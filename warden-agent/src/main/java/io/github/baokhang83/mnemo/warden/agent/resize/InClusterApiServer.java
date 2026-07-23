package io.github.baokhang83.mnemo.warden.agent.resize;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Coordinates for reaching the Kubernetes API server from inside the pod, using the credentials
 * every pod is given automatically &mdash; no RBAC beyond what {@link PodResizeClient}'s own
 * calls need, and no external HTTP client library.
 *
 * <p>Verified against a real pod: {@code KUBERNETES_SERVICE_HOST}/{@code
 * KUBERNETES_SERVICE_PORT} are injected by the kubelet into every pod's environment, and the
 * bound service-account token, its namespace, and the cluster CA certificate are projected at
 * {@code /var/run/secrets/kubernetes.io/serviceaccount} (see {@link #SERVICE_ACCOUNT_DIR}) by
 * the default {@code kube-api-access-*} volume &mdash; no explicit {@code volumeMounts} needed
 * in the pod spec.
 *
 * <p>{@link #bearerToken()} re-reads the token file on every call rather than caching it: bound
 * service-account tokens are time-limited (an hour, by default &mdash; confirmed via a real
 * token's {@code expirationSeconds: 3607}) and the kubelet rotates the file in place before
 * expiry, so a long-lived sidecar that cached the token at startup would eventually start sending
 * an expired one. This rotation-survival behavior is standard, documented kubelet behavior, but
 * wasn't independently observed over a full rotation window here.
 */
public final class InClusterApiServer {

  private static final Path SERVICE_ACCOUNT_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
  private static final String ENV_HOST = "KUBERNETES_SERVICE_HOST";
  private static final String ENV_PORT = "KUBERNETES_SERVICE_PORT";

  private final String host;
  private final int port;
  private final String namespace;
  private final Path tokenFile;
  private final SSLContext trust;

  InClusterApiServer(String host, int port, String namespace, Path tokenFile, SSLContext trust) {
    this.host = host;
    this.port = port;
    this.namespace = namespace;
    this.tokenFile = tokenFile;
    this.trust = trust;
  }

  /** Reads the standard in-cluster environment and mounted service-account volume. */
  public static InClusterApiServer discover() throws IOException, GeneralSecurityException {
    return discover(System::getenv, SERVICE_ACCOUNT_DIR);
  }

  /**
   * Package-private seam so tests can supply a fake environment and a fake service-account
   * directory (e.g. a {@code @TempDir} with a throwaway self-signed cert) instead of the real
   * in-pod paths, which don't exist outside a real pod.
   */
  static InClusterApiServer discover(Function<String, String> env, Path serviceAccountDir)
      throws IOException, GeneralSecurityException {
    String host = requireEnv(env, ENV_HOST);
    int port = Integer.parseInt(requireEnv(env, ENV_PORT));
    String namespace = Files.readString(serviceAccountDir.resolve("namespace")).trim();
    Path tokenFile = serviceAccountDir.resolve("token");
    SSLContext trust = trustingClusterCa(serviceAccountDir.resolve("ca.crt"));
    return new InClusterApiServer(host, port, namespace, tokenFile, trust);
  }

  /** The namespace this pod runs in. */
  public String namespace() {
    return namespace;
  }

  /** The API server's base URI, e.g. {@code https://10.96.0.1:443}. */
  public URI baseUri() {
    return URI.create("https://" + host + ":" + port);
  }

  /** The current bearer token, read fresh from disk (see class javadoc on why it isn't cached). */
  public String bearerToken() throws IOException {
    return Files.readString(tokenFile).trim();
  }

  /** An {@link SSLContext} that trusts only the cluster's own CA, not the JDK's public trust store. */
  public SSLContext sslContext() {
    return trust;
  }

  private static String requireEnv(Function<String, String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is not set; is this process running in a pod?");
    }
    return value;
  }

  private static SSLContext trustingClusterCa(Path caCertFile) throws IOException, GeneralSecurityException {
    CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
    X509Certificate caCert;
    try (InputStream in = Files.newInputStream(caCertFile)) {
      caCert = (X509Certificate) certificateFactory.generateCertificate(in);
    }

    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    trustStore.setCertificateEntry("cluster-ca", caCert);

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
    return sslContext;
  }
}
