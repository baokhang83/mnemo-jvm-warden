package io.github.baokhang83.mnemo.warden.boost.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.baokhang83.mnemo.warden.protocol.MinimalJson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal stand-in for the real {@code BoostApiServer} (warden-agent), so
 * {@link WardenBoostClient} can be tested against a real loopback HTTP round trip without a
 * real agent or Kubernetes cluster — the same inversion {@code BoostApiServerTest} already uses
 * one level down (there, the test is the client; here, the test is the server).
 *
 * <p>{@link #script} lets a test define the exact phase sequence a {@code requestId} reports on
 * successive calls (clamped at the last entry once exhausted) — a purely numeric entry (e.g.
 * {@code "503"}) is treated as a raw HTTP status to return instead of a normal phase, for
 * simulating a transient blip mid-poll.
 */
final class FakeBoostApiServer {

  private final HttpServer server;
  private final String token;
  private final Map<String, List<String>> scripts = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> callIndex = new ConcurrentHashMap<>();
  final AtomicInteger acquireCallCount = new AtomicInteger();
  final AtomicInteger releaseCallCount = new AtomicInteger();

  FakeBoostApiServer(String token) throws IOException {
    this.token = token;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/boost/acquire", exchange -> handleAcquireOrStatus(exchange, true));
    server.createContext("/v1/boost/status", exchange -> handleAcquireOrStatus(exchange, false));
    server.createContext("/v1/boost/release", this::handleRelease);
    server.setExecutor(null);
  }

  void start() {
    server.start();
  }

  void stop() {
    server.stop(0);
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** Defines the phase (or numeric raw-status) sequence {@code requestId} reports, in order. */
  void script(String requestId, String... phases) {
    scripts.put(requestId, List.of(phases));
  }

  private void handleAcquireOrStatus(HttpExchange exchange, boolean isAcquire) throws IOException {
    if (!authorized(exchange)) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return;
    }
    String requestId;
    if (isAcquire) {
      acquireCallCount.incrementAndGet();
      Map<String, Object> body = readJson(exchange);
      requestId = (String) body.get("requestId");
    } else {
      requestId = queryParam(exchange, "requestId");
    }

    List<String> script = scripts.getOrDefault(requestId, List.of("Granted"));
    int index = callIndex.computeIfAbsent(requestId, k -> new AtomicInteger()).getAndIncrement();
    String entry = script.get(Math.min(index, script.size() - 1));

    if (entry.matches("\\d{3}")) {
      respond(exchange, Integer.parseInt(entry), "{\"error\":\"simulated failure\"}");
      return;
    }
    boolean settled = "Granted".equals(entry) || isTerminal(entry);
    int status = isAcquire ? (settled ? 200 : 202) : 200;
    respond(exchange, status, "{\"requestId\":\"" + requestId + "\",\"phase\":\"" + entry + "\",\"message\":null}");
  }

  private void handleRelease(HttpExchange exchange) throws IOException {
    if (!authorized(exchange)) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return;
    }
    releaseCallCount.incrementAndGet();
    Map<String, Object> body = readJson(exchange);
    String requestId = (String) body.get("requestId");
    respond(exchange, 200, "{\"requestId\":\"" + requestId + "\",\"phase\":\"Releasing\",\"message\":null}");
  }

  private static boolean isTerminal(String phase) {
    return switch (phase) {
      case "Completed", "Rejected", "Failed", "Expired", "Orphaned" -> true;
      default -> false;
    };
  }

  private boolean authorized(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    return header != null && header.equals("Bearer " + token);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    return raw.isBlank() ? Map.of() : (Map<String, Object>) MinimalJson.parse(raw);
  }

  private static String queryParam(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
      if (name.equals(key)) {
        return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
      }
    }
    return null;
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
