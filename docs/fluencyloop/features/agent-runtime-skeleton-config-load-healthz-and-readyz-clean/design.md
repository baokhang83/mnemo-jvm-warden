# Design: Agent runtime skeleton: config load, /healthz and /readyz, clean lifecycle, no-op

started: 2026-07-12

The sidecar boots, reads env config, serves `/healthz` + `/readyz`, and shuts down cleanly —
as a no-op, on **zero dependencies beyond the JDK**. The lean spine every later agent slice
builds on.

Key decisions (all flow from §4 — the agent earns its footprint):

- **HTTP via the JDK's `com.sun.net.httpserver.HttpServer`**, not Spring Boot/Quarkus — zero
  deps, single-digit-MB baseline, instant start, for what is two trivial endpoints.
- **Config from the environment** (K8s-native) into an immutable `AgentConfig` record; no YAML
  or config library.
- **Two endpoints even though identical today** — `/readyz` reads a `ready` flag that is the
  seam for M1: it stays `503` until the agent attaches to the target JVM, while `/healthz` stays
  `200` (liveness) so the kubelet doesn't restart a healthy-but-not-ready agent (§2).
- **Logging via the JDK `System.Logger`** — built-in facade, no SLF4J/Logback in the sidecar.

## Class diagram — runtime shapes

```mermaid
classDiagram
  class WardenAgent {
    +main(String[])
  }
  class AgentConfig {
    +int healthPort
    +fromEnv() AgentConfig
  }
  class HealthState {
    +ready() boolean
    +markReady()
    +markNotReady()
  }
  class HealthServer {
    +start()
    +stop()
    +boundPort() int
  }
  WardenAgent --> AgentConfig : creates
  WardenAgent --> HealthState : creates
  WardenAgent --> HealthServer : creates
  HealthServer --> HealthState : reads
  HealthServer ..> HttpServer : wraps (JDK)
```

## Sequence — startup, serving, shutdown

```mermaid
sequenceDiagram
  participant K as kubelet
  participant A as WardenAgent.main
  participant S as HealthServer
  participant H as HealthState
  A->>A: AgentConfig.fromEnv() + log
  A->>H: new HealthState (ready = false)
  A->>S: start(port) + log
  A->>H: markReady()  (no-op: ready once serving)
  A->>A: install shutdown hook, then park
  K->>S: GET /healthz
  S-->>K: 200 (liveness)
  K->>S: GET /readyz
  S->>H: ready()?
  S-->>K: 200
  Note over A,S: SIGTERM
  A->>H: markNotReady()
  A->>S: stop() + log
  A-->>A: clean exit
```

## Constitution check

- **§4 (lean agent):** JDK `HttpServer`, `System.Logger`, env config — the whole module stays
  dependency-free (JUnit remains the only test dependency).
- **§1 (YAGNI):** no config library; liveness is implicit (server responds means alive) rather
  than a second stored flag.
- **§2 (seams):** the `ready` flag is the extension point M1 flips; the endpoint split exists
  before it's needed so M1 adds no surface.
- **§5 (no unverified shrink):** not exercised — this slice has no heap/resize logic.

No conflicts.
