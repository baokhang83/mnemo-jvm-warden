package io.github.baokhang83.mnemo.warden.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal health-endpoint server built on the JDK's {@link HttpServer} (zero dependencies).
 *
 * <ul>
 *   <li>{@code /healthz} always answers {@code 200} &mdash; liveness: the process is up.
 *   <li>{@code /readyz} answers {@code 200} when {@link HealthState#ready()} and {@code 503}
 *       otherwise.
 *   <li>{@code /metrics} answers {@code 200} with {@link AgentMetrics#render()}'s Prometheus text
 *       exposition (W-602) &mdash; one listener, not a second port, for the same reason there's
 *       only ever been one for the probes (constitution &sect;4).
 * </ul>
 */
public final class HealthServer {

  private final int requestedPort;
  private final HealthState health;
  private final AgentMetrics metrics;
  private HttpServer server;

  /**
   * @param port TCP port to bind, or {@code 0} to let the OS pick an ephemeral one (tests)
   * @param health readiness source for {@code /readyz}
   * @param metrics source for {@code /metrics}
   */
  public HealthServer(int port, HealthState health, AgentMetrics metrics) {
    this.requestedPort = port;
    this.health = health;
    this.metrics = metrics;
  }

  /** Binds the port and starts serving. */
  public void start() throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress(requestedPort), 0);
    s.createContext("/healthz", exchange -> respond(exchange, 200, "OK"));
    s.createContext(
        "/readyz",
        exchange -> {
          boolean ready = health.ready();
          respond(exchange, ready ? 200 : 503, ready ? "READY" : "NOT READY");
        });
    s.createContext("/metrics", exchange -> respond(exchange, 200, metrics.render()));
    s.setExecutor(null); // default executor: adequate for low-rate probe traffic
    s.start();
    this.server = s;
    AgentLog.info("health server listening on :" + boundPort() + " (/healthz, /readyz, /metrics)");
  }

  /** The actually bound port &mdash; resolves an ephemeral {@code 0} to the real port. */
  public int boundPort() {
    return server == null ? requestedPort : server.getAddress().getPort();
  }

  /** Stops serving immediately. Safe to call if never started. */
  public void stop() {
    if (server != null) {
      server.stop(0);
      AgentLog.info("health server stopped");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
