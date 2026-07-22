# Design: W-602: Agent self-telemetry - expose resize/abort/RSS/GC-pause metrics in Prometheus text format from warden-agent (zero-dep, hand-rolled exposition)

started: 2026-07-22

An SRE watching `warden-agent` today has only `/healthz`/`/readyz` — up or not, ready or not, no
history of what the agent actually *did*. W-602 adds a `/metrics` endpoint on the same health
port, in Prometheus text exposition format, covering: resize counts, aborts, bytes reclaimed, the
target's current RSS, and the target's cumulative GC pause time.

The ticket names Micrometer/Prometheus, but `warden-agent`'s `pom.xml` states a deliberate
invariant: zero runtime dependencies, plain jar, no shade step. Real Micrometer
(`micrometer-core` + `micrometer-registry-prometheus`) would break that. Since what an SRE
actually needs is the Prometheus *text format* on the wire — not the Micrometer *library* — this
design hand-rolls a small internal registry and a text-format writer in plain JDK, reusing the
`HttpServer` `HealthServer` already runs. See Decisions.

## Class diagram

```mermaid
classDiagram
  class AgentMetrics {
    <<new>>
    +incrementResize(direction, outcome)
    +incrementAborts()
    +addBytesReclaimed(long)
    +setRss(long)
    +setGcStats(collectorName, count, timeMillis)
    +render() String
  }
  class HealthServer {
    <<existing, modified>>
    /healthz, /readyz
    /metrics (new context)
  }
  class IntentWatcher {
    <<existing, modified>>
    pollOnce(): samples RSS + GC every tick,
    increments counters on resize outcome
  }
  class ShrinkSequence {
    <<existing, unchanged>>
    shrinkTo() -> ShrinkOutcome
  }
  class GrowSequence {
    <<existing, unchanged>>
    growTo() -> GrowOutcome
  }
  class HeapController {
    <<existing, unchanged>>
    currentRss()
  }
  class GarbageCollectorMXBean {
    <<JDK, via target.mbeanConnection()>>
    getCollectionCount(), getCollectionTime()
  }
  class WardenAgent {
    <<existing, modified>>
    wires one AgentMetrics into both
    HealthServer and IntentWatcher
  }

  WardenAgent --> AgentMetrics : constructs
  WardenAgent --> HealthServer : passes metrics
  WardenAgent --> IntentWatcher : passes metrics
  HealthServer --> AgentMetrics : render() on GET /metrics
  IntentWatcher --> AgentMetrics : setRss/setGcStats every tick
  IntentWatcher --> HeapController : currentRss()
  IntentWatcher --> GarbageCollectorMXBean : getCollection*()
  IntentWatcher --> ShrinkSequence : shrinkTo()
  IntentWatcher --> GrowSequence : growTo()
  ShrinkSequence ..> IntentWatcher : ShrinkOutcome
  GrowSequence ..> IntentWatcher : GrowOutcome
  IntentWatcher --> AgentMetrics : increment on outcome
```

## Sequence: a poll tick, and a scrape

```mermaid
sequenceDiagram
  participant Timer as poll timer
  participant IW as IntentWatcher
  participant Heap as HeapController
  participant GC as target GC MXBeans
  participant Metrics as AgentMetrics
  participant Seq as Shrink/GrowSequence
  actor Prom as Prometheus

  Note over Timer,Seq: every intentPollInterval tick, whenever a target is attached
  Timer->>IW: pollOnce()
  IW->>Heap: currentRss()
  Heap-->>IW: workingSetBytes
  IW->>Metrics: setRss(workingSetBytes)
  IW->>GC: getCollectionCount()/getCollectionTime() per bean
  GC-->>IW: cumulative count + millis
  IW->>Metrics: setGcStats(collectorName, count, millis)

  alt intent says shrink
    IW->>Seq: shrinkTo(request, limit)
    Seq-->>IW: ShrinkOutcome (Completed | AbortedVerificationFailed)
    alt Completed
      IW->>Metrics: incrementResize(shrink, completed)
      IW->>Metrics: addBytesReclaimed(rssBefore - finalRss)
    else Aborted
      IW->>Metrics: incrementAborts()
    end
  else intent says grow
    IW->>Seq: growTo(request, limit)
    Seq-->>IW: GrowOutcome
    IW->>Metrics: incrementResize(grow, completed)
  end

  Note over Prom,Metrics: independently, Prometheus scrapes on its own schedule
  Prom->>Metrics: GET /metrics (served by HealthServer)
  Metrics-->>Prom: text exposition format (counters + gauges)
```

## Decisions

- **Hand-rolled Prometheus text exposition, not real Micrometer.** `warden-agent`'s `pom.xml`
  states zero runtime dependencies as a deliberate property (plain jar, no shade step). Pulling in
  `micrometer-core` + `micrometer-registry-prometheus` would break that and likely require the
  same shade-plugin treatment `warden-controller` needed for fabric8. What an SRE scrapes is the
  Prometheus text format on the wire, not a specific client library — so a small internal counter
  /gauge registry plus a text-format `render()` in plain JDK satisfies the ticket's actual need
  without the dependency.
- **Metrics live on the existing health port, not a new listener.** `HealthServer` already runs
  one `HttpServer` for `/healthz`/`/readyz`; adding `/metrics` as a third context on the same
  instance avoids a second port, a second env var, and a second thing that can fail to bind.
- **RSS and GC-pause gauges are sampled every `IntentWatcher` poll tick, not at scrape time.**
  Scraping is Prometheus's own schedule and could hit the agent mid-attach or between ticks;
  sampling happens where a live target and a constructed `HeapController` already exist every
  tick regardless of whether an intent resize fires, so the gauges reflect the last observed
  value rather than opening a fresh JMX round-trip per scrape.
- **`ShrinkSequence`/`GrowSequence` stay untouched — metrics recording lives in `IntentWatcher`.**
  Both sequence classes are deliberately documented as depending only on `HeapController` and
  `ResizePort`, "never a concrete GC driver," so the ordering/gate logic is exactly what's under
  test — adding a metrics registry dependency there would be coupling neither class needs.
  `IntentWatcher` is already the one call site that resolves the target, decides the direction,
  and receives the outcome, so it's the natural (and only) place to record the event.
