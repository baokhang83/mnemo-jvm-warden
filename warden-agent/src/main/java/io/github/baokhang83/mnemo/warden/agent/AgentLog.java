package io.github.baokhang83.mnemo.warden.agent;

import java.time.Instant;

/**
 * Minimal, shutdown-safe logger: one line per lifecycle event, written straight to stdout (the
 * container log stream).
 *
 * <p>Why not {@code System.Logger}? Its default {@code java.util.logging} backend registers its
 * own JVM shutdown hook that removes all handlers; it runs concurrently with the agent's
 * shutdown hook, so lifecycle lines logged during shutdown are non-deterministically dropped.
 * stdout is never torn down, so logging there is reliable end-to-end &mdash; and for a sidecar,
 * stdout <em>is</em> the log stream (12-factor). This stays dependency-free per &sect;4.
 */
final class AgentLog {

  private AgentLog() {}

  static void info(String message) {
    System.out.println(Instant.now() + " INFO  warden-agent - " + message);
  }
}
