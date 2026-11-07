package io.github.baokhang83.mnemo.warden.controller.boost;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, process-local transition counters plus live gauges from durable request status. */
public final class BoostTelemetry {
  private static final Logger log = LoggerFactory.getLogger(BoostTelemetry.class);
  private static final EnumMap<BoostPhase, LongAdder> TRANSITIONS = new EnumMap<>(BoostPhase.class);
  private static final LongAdder ACQUISITIONS = new LongAdder();
  private static final DoubleAdder ACQUISITION_SECONDS = new DoubleAdder();
  private static final LongAdder RELEASES = new LongAdder();
  private static final DoubleAdder RELEASE_SECONDS = new DoubleAdder();
  static { for (BoostPhase phase : BoostPhase.values()) TRANSITIONS.put(phase, new LongAdder()); }
  private BoostTelemetry() {}

  /** Called only after the corresponding compare-and-set has succeeded. */
  public static void changed(KubernetesClient client, WardenBoost old, WardenBoost saved) {
    var previous = old.getStatus(); var status = saved.getStatus();
    if (status == null || status.getPhase() == null) return;
    BoostPhase phase = status.getPhase();
    BoostPhase oldPhase = previous == null ? null : previous.getPhase();
    if (phase != oldPhase) {
      TRANSITIONS.get(phase).increment();
      if (phase == BoostPhase.Granted) duration(saved.getMetadata().getCreationTimestamp(), status.getGrantedAt(), ACQUISITIONS, ACQUISITION_SECONDS);
      if (phase == BoostPhase.Completed) duration(workFinished(previous), status.getCompletedAt(), RELEASES, RELEASE_SECONDS);
    }
    String oldReason = readyReason(previous == null ? null : previous.getConditions());
    String reason = readyReason(status.getConditions());
    String oldTransition = previous == null ? null : previous.getTransitionId();
    if (phase != oldPhase || !java.util.Objects.equals(oldReason, reason)
        || !java.util.Objects.equals(oldTransition, status.getTransitionId())) emitEvent(client, saved, reason);
  }

  private static void duration(String from, String to, LongAdder count, DoubleAdder total) {
    if (from == null || to == null) return;
    try {
      double seconds = Duration.between(Instant.parse(from), Instant.parse(to)).toNanos() / 1_000_000_000.0;
      if (seconds >= 0 && Double.isFinite(seconds)) { count.increment(); total.add(seconds); }
    } catch (RuntimeException ignored) { /* malformed timestamps do not break reconciliation */ }
  }
  private static String workFinished(io.github.baokhang83.mnemo.warden.crd.WardenBoostStatus status) {
    if (status == null || status.getConditions() == null) return null;
    return status.getConditions().stream().filter(c -> "WorkFinished".equals(c.getType()) && "True".equals(c.getStatus()))
        .map(Condition::getLastTransitionTime).findFirst().orElse(null);
  }
  private static String readyReason(List<Condition> conditions) {
    if (conditions == null) return null;
    return conditions.stream().filter(c -> "Ready".equals(c.getType())).map(Condition::getReason).findFirst().orElse(null);
  }
  private static void emitEvent(KubernetesClient client, WardenBoost boost, String reason) {
    if (!"true".equalsIgnoreCase(System.getenv("WARDEN_BOOST_EVENTS_ENABLED"))) return;
    try {
      var metadata = boost.getMetadata(); var status = boost.getStatus();
      String now = Instant.now().toString();
      String message = "requestUid=" + metadata.getUid() + " transitionId=" + status.getTransitionId()
          + " phase=" + status.getPhase() + " reason=" + reason;
      boolean warning = status.getPhase() == BoostPhase.Failed || status.getPhase() == BoostPhase.Expired
          || status.getPhase() == BoostPhase.Rejected || "ReleaseBlocked".equals(reason)
          || "UnsupportedJvm".equals(reason) || "PreparationFailed".equals(reason)
          || "StaleAcknowledgement".equals(reason);
      var event = new EventBuilder().withNewMetadata().withGenerateName("warden-boost-")
          .withNamespace(metadata.getNamespace()).endMetadata().withType(warning ? "Warning" : "Normal")
          .withReason("Boost" + status.getPhase()).withMessage(message)
          .withNewInvolvedObject().withApiVersion(boost.getApiVersion()).withKind("WardenBoost")
          .withName(metadata.getName()).withNamespace(metadata.getNamespace()).withUid(metadata.getUid()).endInvolvedObject()
          .withNewSource().withComponent("warden-controller").endSource()
          .withFirstTimestamp(now).withLastTimestamp(now).withCount(1).build();
      client.v1().events().inNamespace(metadata.getNamespace()).resource(event).create();
    } catch (RuntimeException e) { log.warn("could not emit Boost event: {}", e.getMessage()); }
  }

  public static HttpServer start(KubernetesClient client, int port) throws IOException {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid Boost metrics port");
    var server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/metrics", exchange -> {
      try {
        if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
        var boosts = client.resources(WardenBoost.class).inAnyNamespace().list().getItems();
        byte[] result = render(boosts).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, result.length);
        try (var out = exchange.getResponseBody()) { out.write(result); }
      } catch (RuntimeException e) {
        log.warn("could not collect Boost metrics: {}", e.getMessage());
        exchange.sendResponseHeaders(503, -1);
      } finally { exchange.close(); }
    });
    server.start(); return server;
  }

  static String render(List<WardenBoost> boosts) {
    long active = 0, expired = 0, blocked = 0;
    for (WardenBoost boost : boosts) {
      var status = boost.getStatus(); if (status == null) continue;
      if (status.getPhase() == BoostPhase.Granted) active++;
      if (status.getPhase() == BoostPhase.Expired) expired++;
      if (status.getPhase() == BoostPhase.Releasing && "ReleaseBlocked".equals(readyReason(status.getConditions()))) blocked++;
    }
    var output = new StringBuilder();
    output.append("# TYPE warden_boost_active_grants gauge\nwarden_boost_active_grants ").append(active).append('\n');
    output.append("# TYPE warden_boost_expired_grants gauge\nwarden_boost_expired_grants ").append(expired).append('\n');
    output.append("# TYPE warden_boost_blocked_releases gauge\nwarden_boost_blocked_releases ").append(blocked).append('\n');
    output.append("# TYPE warden_boost_transitions_total counter\n");
    for (BoostPhase phase : BoostPhase.values()) output.append("warden_boost_transitions_total{phase=\"")
        .append(phase).append("\"} ").append(TRANSITIONS.get(phase).sum()).append('\n');
    output.append("# TYPE warden_boost_acquisition_duration_seconds summary\nwarden_boost_acquisition_duration_seconds_sum ")
        .append(ACQUISITION_SECONDS.sum()).append("\nwarden_boost_acquisition_duration_seconds_count ").append(ACQUISITIONS.sum()).append('\n');
    output.append("# TYPE warden_boost_release_duration_seconds summary\nwarden_boost_release_duration_seconds_sum ")
        .append(RELEASE_SECONDS.sum()).append("\nwarden_boost_release_duration_seconds_count ").append(RELEASES.sum()).append('\n');
    return output.toString();
  }
}
