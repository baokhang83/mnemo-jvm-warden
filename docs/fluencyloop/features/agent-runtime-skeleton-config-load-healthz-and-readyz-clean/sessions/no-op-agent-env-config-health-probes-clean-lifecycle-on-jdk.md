# Session: No-op agent: env config, health probes, clean lifecycle on JDK HttpServer

- **intent:** No-op agent: env config, health probes, clean lifecycle on JDK HttpServer
- **started:** 2026-07-12

---

## Knowledge transfer

- **`WardenAgent`** — composition root / `main`: loads `AgentConfig`, creates `HealthState` and
  `HealthServer`, marks ready, installs a shutdown hook, then parks on a latch until SIGTERM. ·
  status: documented
- **`AgentConfig`** — immutable record read from the environment (`WARDEN_HEALTH_PORT`, default
  8080) with validation; a package-private `fromEnv(Function)` seam lets tests inject a fake env
  without mutating the process environment. · status: documented
- **`HealthState`** — thread-safe `ready` flag shared between the lifecycle thread and the HTTP
  handlers. Liveness is *not* stored (server responding means alive); readiness is the flag M1
  will gate on. · status: documented
- **`HealthServer`** — wraps the JDK `com.sun.net.httpserver.HttpServer`. `/healthz` always 200
  (liveness); `/readyz` returns 200/503 from `HealthState.ready()`. Binds port 0 for an ephemeral
  test port, exposed via `boundPort()`. · status: documented
- **Kubernetes liveness vs readiness** — `/healthz` failing means the kubelet restarts the
  container; `/readyz` failing means it stops routing but leaves it running. That split is why a
  not-yet-ready agent must not fail liveness. · status: documented

---

## Decision: HTTP via the JDK HttpServer, not Spring Boot / a framework

- **where:** `warden-agent` (`HealthServer`, and the absence of any web-framework dependency)
- **why:** The agent is a memory-efficiency product; a 120-250 MB Spring Boot baseline (or any
  DI-container framework) for two trivial health endpoints would make the right-sizer a bigger
  memory hog than the waste it reclaims. The JDK's `HttpServer` is zero-dependency, single-digit
  MB, and starts instantly — exactly enough for probes now and the small surface the agent needs
  later.
- **alternative:** Spring Boot / Quarkus / Javalin — rejected: a web platform for what is an
  HTTP listener; the framework weight is the very cost §4 exists to prevent.
- **design:** [../design.md](../design.md) — the `HealthServer` shape.
- **constitution:** §4 (agent earns its footprint)
- **trust:** ✓ verified — `mvn verify` green; agent runs as a no-op with both probes answering.

## Decision: lifecycle logging to stdout (AgentLog), not System.Logger

- **where:** `warden-agent/.../agent/AgentLog.java` (and its use in `WardenAgent`/`HealthServer`)
- **why:** `System.Logger`'s `java.util.logging` backend registers its own JVM shutdown hook that
  removes all handlers; it races the agent's shutdown hook, so lifecycle lines logged during
  shutdown are dropped non-deterministically (observed: 0-1 of 2 shutdown lines across runs).
  Writing lifecycle events straight to stdout is reliable end-to-end (stdout is never torn down)
  and is the idiomatic container log stream (12-factor) — while staying dependency-free.
- **alternative 1:** Keep `System.Logger` — rejected: fails the "clean shutdown log" acceptance
  criterion intermittently. **alternative 2:** Add SLF4J/Logback — rejected: pulls a logging
  framework into the lean agent for a problem plain stdout solves (§4).
- **constitution:** §4 (lean agent), §1 (a ~12-line stdout logger, not a logging framework)
- **trust:** ✓ verified — surfaced by running the jar under SIGTERM; after the change both
  shutdown lines appear in 5/5 runs.

## Decision: two endpoints and a readiness flag now, though identical today

- **where:** `HealthServer` (`/healthz` vs `/readyz`), `HealthState.ready`
- **why:** `/readyz` reads a `ready` flag that is the seam for M1 — it will stay 503 until the
  agent has attached to the target JVM, while `/healthz` stays 200 so the kubelet doesn't restart
  a healthy-but-not-ready agent. Building the split now means M1 flips a flag rather than adding
  endpoints.
- **alternative:** One combined health endpoint now — rejected: would collapse liveness and
  readiness, which M1 needs distinct, and force an API change later.
- **design:** [../design.md](../design.md) — the two endpoint shapes under `HealthServer`.
- **constitution:** §2 (abstractions at the seams)
- **trust:** ✓ verified — `HealthServerTest.readyzTracksReadiness` exercises 503 to 200 to 503.
