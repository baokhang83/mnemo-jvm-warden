# Session: IntentWatcher resolves-once + gc_supported gauge

- **intent:** IntentWatcher resolves-once + gc_supported gauge
- **started:** 2026-07-22

<!--
FluencyLoop Stage 3 — a session is a slice of the build. It holds two persistent records:

1. Knowledge transfer — what the developer was actually made fluent in during this slice.
2. Decisions — the genuine forks, one `## Decision:` block each.

Both are appended at the slice boundary, from the *live* teaching. One bullet per field, so it
renders one-per-line (plain `key: value` lines collapse into a paragraph when rendered). No
`commits:` field: the feature is a branch, so the PR view derives commits live from git.

DECISION fields:
  where        — file/area the decision lives in (NOT a line number — survives refactoring)
  why          — the rationale, taught live before it was written
  alternative  — the rejected option and why (this is what makes it rationale, not description)
  design       — (optional) ../design.md#anchor — the diagram this decision shaped or used
  constitution — (optional) §N — the principle this decision serves or trades off against
  trust        — ✓ verified | ⚠ not independently verified  (about the DECISION, never the person)

KNOWLEDGE-TRANSFER fields (one bullet per component/role/mechanism explained):
  subject      — the component, role, or mechanism (e.g. a class, an agent, a rule)
  what         — what it does, and under what conditions it does it
  status       — documented (captured here) | follow-up (worth covering later)
  Describe the WORK, never a person: no competence, no prior-knowledge, no "who learned what".
  These files are committed and name an identifiable author via git — keep them GDPR-safe.

Delete this comment and the examples below once real content lands.
-->

---

## Knowledge transfer

- **`GcDetector`/`GcCapabilities`/`AttachedHeapController`** — already correctly classify ZGC,
  Shenandoah, G1, and everything else (`Collector.OTHER`) before this feature; `OTHER` maps to
  `supportsUncommit = false`, so `GcCapabilities.supported()` is false, and
  `AttachedHeapController.forTarget()` throws `UnsupportedCollectorException` on construction.
  This feature does not touch that matrix — it was already complete. · status: documented
- **`TargetHeapControllerResolver.resolve`** — the single point that resolves a target's
  `HeapController` (or its unsupported verdict) and caches it keyed on `AttachedJvm` reference
  identity; `IntentWatcher`'s `pollOnce()` (resize path) and `sampleMetrics()` (RSS/GC gauge
  sampling) both go through it, so the unsupported-collector log line and metric fire exactly once
  per attach, not once per poll tick. Extracted out of `IntentWatcher` mid-slice for testability
  (see the extraction decision below). · status: documented
- **`AgentMetrics.warden_gc_supported`** — a single-valued gauge (one collector label at a time,
  matching how `warden_target_rss_working_set_bytes` already tracks just the current attach) that
  is absent from `/metrics` output entirely until a target has actually been resolved once —
  mirrors the existing pattern for `gcStatsByCollector`. · status: documented

---

## Decision: cache the unsupported-collector verdict per target identity instead of retrying every tick

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/intent/IntentWatcher.java`
- **why:** an unsupported collector is a permanent fact about an attach, not a transient failure; retrying AttachedHeapController.forTarget() every poll tick redid the cgroup-walk and re-logged a generic error forever
- **alternative:** let UnsupportedCollectorException keep propagating to run()'s generic catch-all — rejected: indistinguishable from a real transient poll failure, and re-attempts a construction whose outcome cannot change
- **design:** ../design.md#sequence-resolving-a-targets-heapcontroller-once-per-attach
- **constitution:** §4
- **trust:** ⚠ not independently verified

## Decision: expose GC-support state as a Prometheus gauge, not just a log line

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/metrics/AgentMetrics.java`
- **why:** operators need to alert on read-only mode, not grep logs for it; a gauge on the existing /metrics endpoint (W-602) is directly wired into whatever monitoring already scrapes Warden
- **alternative:** log-only signal — rejected: not alertable, easy to miss on a fleet of pods
- **design:** ../design.md#class-diagram
- **constitution:** §4
- **trust:** ⚠ not independently verified

## Decision: extract TargetHeapControllerResolver out of IntentWatcher for testability

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/intent/TargetHeapControllerResolver.java`
- **why:** IntentWatcher itself has no testable seam (AttachSupervisor's known-target constructor is package-private to a different package, PodResizeClient.forInClusterAgent() is a static in-cluster call) so the resolve-once/gate-unsupported logic was untestable in place; pulling it into its own class lets a real-JVM test (SpawnedJvm/TargetAttacher) exercise it directly, the same style as the existing AttachedHeapControllerTest one layer down
- **alternative:** leave the logic inline in IntentWatcher and only cover it via AgentMetricsTest's gauge assertions — rejected: would leave the actual resolve-once/cache/one-log behavior completely unverified, only its side effect on the metrics object
- **design:** ../design.md#class-diagram
- **constitution:** §3
- **trust:** ⚠ not independently verified
