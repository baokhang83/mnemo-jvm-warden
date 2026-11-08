package io.github.baokhang83.mnemo.warden.boost.client;

import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import io.github.baokhang83.mnemo.warden.protocol.MinimalJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Entry point for B9's Java lease client: talks only to the B8 local sidecar API
 * (loopback-only, never the Kubernetes API), so embedding this in an application needs no
 * Kubernetes credentials at all &mdash; the dev plan's explicit requirement.
 *
 * <pre>{@code
 * try (BoostLease lease = WardenBoostClient.discover().acquireBoost(
 *         "index-rebuild", Duration.ofMinutes(2))) {
 *   rebuildIndex();
 * }
 * }</pre>
 *
 * <p>{@link #acquireBoost} returns only after a durable {@code Granted} &mdash; never for a
 * merely {@code Pending} request. If it observes a non-granted phase treated as terminal by
 * the client, it throws {@link BoostRejectedException}; cleanup can still be pending. If this
 * client's own {@code timeout} elapses first, it throws {@link BoostAcquisitionTimeoutException}
 * and does not complete the request: a grant may still race server-side. The timeout does not
 * extend the operator's acquisition deadline. It is checked between HTTP calls, so an in-flight
 * call can make the total wait exceed it. Preserve the request ID and reconcile uncertain outcomes.
 */
public final class WardenBoostClient {

  private static final String ENV_BOOST_PORT = "WARDEN_BOOST_PORT";
  private static final String ENV_BOOST_TOKEN_PATH = "WARDEN_BOOST_TOKEN_PATH";
  private static final int DEFAULT_BOOST_PORT = 8090;
  private static final String DEFAULT_TOKEN_PATH = "/var/run/warden/boost/token";
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI baseUri;
  private final Path tokenPath;
  private final HttpClient httpClient;
  private final Duration pollInterval;

  /**
   * Connects using the agent's own defaults ({@code WARDEN_BOOST_PORT}=8090, {@code
   * WARDEN_BOOST_TOKEN_PATH}=/var/run/warden/boost/token) unless overridden by the environment
   * &mdash; matching {@code AgentConfig}'s defaults exactly, so an app container needs no extra
   * env vars at all if it accepts them, only the shared token volume mount.
   */
  public static WardenBoostClient discover() {
    return discover(System::getenv);
  }

  /** Package-private seam so tests can supply a fake environment. */
  static WardenBoostClient discover(Function<String, String> env) {
    int port = parsePort(env.apply(ENV_BOOST_PORT));
    String configuredTokenPath = env.apply(ENV_BOOST_TOKEN_PATH);
    Path tokenPath = Path.of(
        (configuredTokenPath == null || configuredTokenPath.isBlank())
            ? DEFAULT_TOKEN_PATH
            : configuredTokenPath.trim());
    return new WardenBoostClient(
        URI.create("http://127.0.0.1:" + port), tokenPath, HttpClient.newHttpClient(), DEFAULT_POLL_INTERVAL);
  }

  /** Package-private seam so tests can point at a fake local server and shorten polling. */
  WardenBoostClient(URI baseUri, Path tokenPath, HttpClient httpClient, Duration pollInterval) {
    this.baseUri = baseUri;
    this.tokenPath = tokenPath;
    this.httpClient = httpClient;
    this.pollInterval = pollInterval;
  }

  /** Acquires a new boost, generating a stable request ID. See the class javadoc. */
  public BoostLease acquireBoost(String boost, Duration timeout) throws IOException, InterruptedException {
    return acquireBoost(boost, UUID.randomUUID().toString(), timeout);
  }

  /**
   * Acquires (or resumes waiting on) a boost under a caller-supplied, stable request ID &mdash;
   * the retry path for a {@code requestId} recovered from a previous
   * {@link BoostAcquisitionTimeoutException}. See the class javadoc.
   */
  public BoostLease acquireBoost(String boost, String requestId, Duration timeout)
      throws IOException, InterruptedException {
    if (boost == null || boost.isBlank()) {
      throw new IllegalArgumentException("boost is required");
    }
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId is required");
    }
    Instant deadline = Instant.now().plus(timeout);
    boolean created = false;
    String lastPhase = "Pending";
    while (true) {
      HttpResponse<String> response = null;
      try {
        response = created
            ? rawGet("/v1/boost/status?requestId=" + encode(requestId))
            : rawPost("/v1/boost/acquire", acquireBody(boost, requestId));
      } catch (IOException networkBlip) {
        // Transient — fall through to the deadline/backoff check below rather than failing fast.
      }
      if (response != null) {
        if (response.statusCode() == 401) {
          throw new IOException("boost API rejected the bearer token (401): " + response.body());
        }
        if (response.statusCode() == 400) {
          throw new IOException("boost API rejected the request (400): " + response.body());
        }
        if (response.statusCode() == 200 || response.statusCode() == 202) {
          created = true;
          BoostStatus status = parseStatus(response.body());
          lastPhase = status.phase();
          if (status.isGranted()) {
            return new BoostLease(this, requestId);
          }
          if (status.isTerminal()) {
            throw new BoostRejectedException(requestId, status.phase(), status.message());
          }
          // Pending/Preparing/Releasing — keep polling below.
        }
        // Any other status (404/409/5xx mid-poll) is treated the same as a network blip.
      }
      if (!Instant.now().isBefore(deadline)) {
        throw new BoostAcquisitionTimeoutException(requestId, lastPhase);
      }
      Thread.sleep(pollInterval.toMillis());
    }
  }

  /** Package-private introspection seam for tests confirming {@link #discover} resolved correctly. */
  URI baseUri() {
    return baseUri;
  }

  /** Package-private introspection seam for tests confirming {@link #discover} resolved correctly. */
  Path tokenPath() {
    return tokenPath;
  }

  /** Looks up a known request ID directly, without creating a {@link BoostLease}. */
  public BoostStatus inspect(String requestId) throws IOException, InterruptedException {
    HttpResponse<String> response = rawGet("/v1/boost/status?requestId=" + encode(requestId));
    if (response.statusCode() != 200) {
      throw new IOException("inspect " + requestId + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return parseStatus(response.body());
  }

  BoostStatus release(String requestId) throws IOException, InterruptedException {
    HttpResponse<String> response = rawPost("/v1/boost/release", releaseBody(requestId));
    if (response.statusCode() != 200) {
      throw new IOException("release " + requestId + " failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return parseStatus(response.body());
  }

  private HttpResponse<String> rawPost(String path, String body) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
        .timeout(REQUEST_TIMEOUT)
        .header("Authorization", "Bearer " + bearerToken())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> rawGet(String pathWithQuery) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(pathWithQuery))
        .timeout(REQUEST_TIMEOUT)
        .header("Authorization", "Bearer " + bearerToken())
        .GET()
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Re-read fresh on every call, never cached in a field — a sidecar restart regenerates the
   * token, and this client may live far longer than any single token's validity, mirroring
   * {@code InClusterApiServer.bearerToken()}'s own rationale.
   */
  private String bearerToken() throws IOException {
    return Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
  }

  @SuppressWarnings("unchecked")
  private static BoostStatus parseStatus(String json) {
    Map<String, Object> map = (Map<String, Object>) MinimalJson.parse(json);
    return new BoostStatus((String) map.get("requestId"), (String) map.get("phase"), (String) map.get("message"));
  }

  private static String acquireBody(String boost, String requestId) {
    return "{\"boost\":" + JsonString.quote(boost) + ",\"requestId\":" + JsonString.quote(requestId) + "}";
  }

  private static String releaseBody(String requestId) {
    return "{\"requestId\":" + JsonString.quote(requestId) + "}";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static int parsePort(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_BOOST_PORT;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(ENV_BOOST_PORT + " must be an integer, got: " + raw, e);
    }
  }
}
