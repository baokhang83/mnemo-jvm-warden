package io.github.baokhang83.mnemo.warden.agent.boost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.resize.MinimalJson;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The B8 application-facing local API: a loopback-only HTTP server the app container in the same
 * pod calls to acquire/check/release a Boost, without ever touching Kubernetes credentials
 * itself. Three endpoints, all requiring {@code Authorization: Bearer <token>} (see {@link
 * BoostToken}):
 *
 * <ul>
 *   <li>{@code POST /v1/boost/acquire} &mdash; {@code {"boost":"<name>","requestId":"<optional>"}}.
 *       Idempotent on {@code requestId} (the "stable ID"); blocks up to the configured bounded
 *       wait for {@code Granted} or a terminal phase, returning {@code 202} with the current
 *       phase if the wait elapses first so the caller polls {@code /status} instead of hanging.
 *   <li>{@code GET /v1/boost/status?requestId=...} &mdash; re-reads the current phase.
 *   <li>{@code POST /v1/boost/release} &mdash; {@code {"requestId":"..."}}; requests completion.
 * </ul>
 *
 * <p>Binds the loopback address specifically, unlike {@code HealthServer}'s wildcard bind:
 * loopback binding alone is not an authorization boundary between containers sharing a pod
 * network (any container in the pod can reach a loopback port), so the bearer token above is the
 * real boundary and the loopback bind is defense-in-depth on top of it, not a substitute for it.
 *
 * <p>Enforces "one outstanding acquisition per process" (the "request limits" acceptance
 * criterion) in memory: a second {@code acquire} for a different, still-active {@code requestId}
 * is rejected with {@code 409} and the existing one. This is a client-experience convenience on
 * top of &mdash; not a replacement for &mdash; the controller's own per-pod {@code TargetClaim}
 * exclusivity, which still holds across agent restarts.
 */
public final class BoostApiServer {

  private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

  private final int requestedPort;
  private final String token;
  private final BoostRequestPort client;
  private final String policyRef;
  private final String podName;
  private final String podUid;
  private final String containerName;
  private final Duration acquireWait;
  private final Object lock = new Object();
  private volatile String outstandingRequestId;
  private HttpServer server;

  public BoostApiServer(
      int port,
      String token,
      BoostRequestPort client,
      String policyRef,
      String podName,
      String podUid,
      String containerName,
      Duration acquireWait) {
    this.requestedPort = port;
    this.token = token;
    this.client = client;
    this.policyRef = policyRef;
    this.podName = podName;
    this.podUid = podUid;
    this.containerName = containerName;
    this.acquireWait = acquireWait;
  }

  /** Binds the loopback address and starts serving. */
  public void start() throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
    s.createContext("/v1/boost/acquire", this::handleAcquire);
    s.createContext("/v1/boost/status", this::handleStatus);
    s.createContext("/v1/boost/release", this::handleRelease);
    s.setExecutor(null); // default executor: adequate for one app's own low-rate local calls
    s.start();
    this.server = s;
    AgentLog.info("boost API listening on 127.0.0.1:" + boundPort() + " (/v1/boost/acquire, /status, /release)");
  }

  /** The actually bound port &mdash; resolves an ephemeral {@code 0} to the real port. */
  public int boundPort() {
    return server == null ? requestedPort : server.getAddress().getPort();
  }

  /** Stops serving immediately. Safe to call if never started. */
  public void stop() {
    if (server != null) {
      server.stop(0);
      AgentLog.info("boost API stopped");
    }
  }

  private void handleAcquire(HttpExchange exchange) throws IOException {
    if (!authorize(exchange)) {
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      respondError(exchange, 405, "POST only");
      return;
    }
    Map<String, Object> body = readJsonBody(exchange);
    String boost = stringField(body, "boost");
    String requestId = stringField(body, "requestId");
    if (boost == null || boost.isBlank()) {
      respondError(exchange, 400, "\"boost\" is required");
      return;
    }
    if (requestId != null && !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
      respondError(exchange, 400, "invalid requestId");
      return;
    }

    String id;
    synchronized (lock) {
      String existing = outstandingRequestId;
      if (existing != null && !existing.equals(requestId)) {
        Optional<BoostSnapshot> existingSnapshot = safeGet(existing);
        if (existingSnapshot.isPresent() && !isTerminal(existingSnapshot.get().phase())) {
          respondJson(exchange, 409, snapshotJson(existingSnapshot.get()));
          return;
        }
        outstandingRequestId = null; // the previous slot holder is terminal or gone; free it
      }
      id = (requestId != null) ? requestId : UUID.randomUUID().toString();
      outstandingRequestId = id;
    }

    BoostSnapshot snapshot;
    try {
      snapshot = client.getOrCreate(id, policyRef, podName, podUid, containerName, boost);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respondError(exchange, 502, "interrupted creating boost request");
      return;
    } catch (IOException e) {
      respondError(exchange, 502, "could not create boost request: " + e.getMessage());
      return;
    }

    snapshot = awaitSettled(id, snapshot);
    boolean settled = "Granted".equals(snapshot.phase()) || isTerminal(snapshot.phase());
    respondJson(exchange, settled ? 200 : 202, snapshotJson(snapshot));
  }

  /** Polls until {@code Granted}/terminal or the bounded wait elapses &mdash; never indefinitely. */
  private BoostSnapshot awaitSettled(String id, BoostSnapshot initial) {
    BoostSnapshot snapshot = initial;
    Instant deadline = Instant.now().plus(acquireWait);
    while (!"Granted".equals(snapshot.phase()) && !isTerminal(snapshot.phase()) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      Optional<BoostSnapshot> refreshed = safeGet(id);
      if (refreshed.isEmpty()) {
        break; // keep the last known snapshot rather than failing the whole acquire call
      }
      snapshot = refreshed.get();
    }
    return snapshot;
  }

  private void handleStatus(HttpExchange exchange) throws IOException {
    if (!authorize(exchange)) {
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod())) {
      respondError(exchange, 405, "GET only");
      return;
    }
    String requestId = queryParam(exchange, "requestId");
    if (requestId == null || requestId.isBlank()) {
      respondError(exchange, 400, "requestId is required");
      return;
    }
    try {
      Optional<BoostSnapshot> snapshot = client.get(requestId);
      if (snapshot.isEmpty()) {
        respondError(exchange, 404, "no such boost request");
        return;
      }
      respondJson(exchange, 200, snapshotJson(snapshot.get()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respondError(exchange, 502, "interrupted reading boost status");
    } catch (IOException e) {
      respondError(exchange, 502, "could not read boost status: " + e.getMessage());
    }
  }

  private void handleRelease(HttpExchange exchange) throws IOException {
    if (!authorize(exchange)) {
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      respondError(exchange, 405, "POST only");
      return;
    }
    Map<String, Object> body = readJsonBody(exchange);
    String requestId = stringField(body, "requestId");
    if (requestId == null || requestId.isBlank()) {
      respondError(exchange, 400, "requestId is required");
      return;
    }
    try {
      Optional<BoostSnapshot> current = client.get(requestId);
      if (current.isEmpty()) {
        respondError(exchange, 404, "no such boost request");
        return;
      }
      BoostSnapshot released = client.requestCompletion(requestId, current.get().resourceVersion());
      respondJson(exchange, 200, snapshotJson(released));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respondError(exchange, 502, "interrupted releasing boost request");
    } catch (IOException e) {
      respondError(exchange, 502, "could not release boost request: " + e.getMessage());
    }
  }

  private Optional<BoostSnapshot> safeGet(String id) {
    try {
      return client.get(id);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static boolean isTerminal(String phase) {
    return switch (phase) {
      case "Completed", "Rejected", "Failed", "Expired", "Orphaned" -> true;
      default -> false;
    };
  }

  private boolean authorize(HttpExchange exchange) throws IOException {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    String presented = (header != null && header.startsWith("Bearer ")) ? header.substring("Bearer ".length()) : null;
    if (!BoostToken.matches(presented, token)) {
      respondError(exchange, 401, "missing or invalid bearer token");
      return false;
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (raw.isBlank()) {
      return Map.of();
    }
    try {
      return (Map<String, Object>) MinimalJson.parse(raw);
    } catch (RuntimeException e) {
      return Map.of(); // malformed body surfaces as a missing-required-field 400, not a 500
    }
  }

  private static String stringField(Map<String, Object> body, String name) {
    Object value = body.get(name);
    return value instanceof String s ? s : null;
  }

  private static String queryParam(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = urlDecode(eq >= 0 ? pair.substring(0, eq) : pair);
      if (name.equals(key)) {
        return eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
      }
    }
    return null;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String snapshotJson(BoostSnapshot snapshot) {
    return "{\"requestId\":" + JsonString.quote(snapshot.name())
        + ",\"phase\":" + JsonString.quote(snapshot.phase())
        + ",\"message\":" + (snapshot.message() == null ? "null" : JsonString.quote(snapshot.message()))
        + "}";
  }

  private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
    respondJson(exchange, status, "{\"error\":" + JsonString.quote(message) + "}");
  }
}
