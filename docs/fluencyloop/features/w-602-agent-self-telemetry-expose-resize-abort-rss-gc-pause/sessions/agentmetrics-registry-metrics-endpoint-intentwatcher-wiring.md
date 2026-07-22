# Session: AgentMetrics registry + /metrics endpoint + IntentWatcher wiring

- **intent:** AgentMetrics registry + /metrics endpoint + IntentWatcher wiring
- **started:** 2026-07-22

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **AgentMetrics** — a plain-JDK counter/gauge registry covering exactly the fixed metric set W-602 names (resizes by direction, aborts, bytes reclaimed, target RSS, per-collector GC count/time), not a generic metrics-library replacement; `render()` writes Prometheus text exposition format 0.0.4 directly (verified by hand against a mixed-state sample: correct `# HELP`/`# TYPE` pairing, `_total` suffix on counters, `_seconds` on time). status: documented
- **HealthServer's `/metrics` context** — a third context on the same `HttpServer` `/healthz`/`/readyz` already run, so no new port/env var; serves `AgentMetrics.render()` verbatim on every GET, no caching of the response itself (the registry's own gauges/counters are what's cached/sampled, not the rendered text). status: documented
- **IntentWatcher as the single metrics call site** — `ShrinkSequence`/`GrowSequence` stay untouched; `IntentWatcher.pollOnce()` samples RSS + GC-bean stats every tick a target is attached (via `sampleMetrics`, wrapped in its own try/catch per constitution §12 so a sampling failure can never block that tick's real resize/abort decision), and increments resize/abort/bytes-reclaimed counters right where `ShrinkOutcome`/`GrowOutcome` are already produced. Bytes reclaimed for a completed shrink reuses the same tick's RSS sample as the "before" baseline, rather than reading RSS twice. status: documented
- **GC pause metrics source** — `GarbageCollectorMXBean.getCollectionCount()`/`getCollectionTime()` on the target's beans (via `target.mbeanConnection()`, the same JMX path `GcDetector`/`DeepGc`/`G1PeriodicGc` already use) are already cumulative, monotonic totals — exactly what a Prometheus counter expects — so they're exposed directly per collector-bean name, no separate accumulation needed. A bean reporting `-1` for either value (undefined) is skipped rather than recorded as a bogus reading. status: documented

---

## Decision: cache the per-target HeapController in IntentWatcher instead of reconstructing it every poll tick

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/intent/IntentWatcher.java`
- **why:** sampling RSS/GC gauges every tick (W-602) needed a HeapController on every tick regardless of whether an intent resize fires; AttachedHeapController.forTarget resolves the target's cgroup directory via a bounded-depth (12-level) filesystem walk, which is stable for the target JVM's whole lifetime, so redoing it every ~5s tick forever would be a recurring cost with no payoff, directly against constitution section 4 (the agent earns its footprint)
- **alternative:** construct a fresh AttachedHeapController every poll tick, same as the existing shrink/grow code already did - rejected: redoes the bounded-depth cgroup filesystem walk every tick just to read a gauge
- **design:** ../design.md#sequence-a-poll-tick-and-a-scrape
- **constitution:** §4
- **trust:** ✓ verified
