# Session: Build the Prometheus metric source and verify it against a real Prometheus

- **intent:** Build the Prometheus metric source and verify it against a real Prometheus
- **started:** 2026-07-21

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

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`vector(1)` as a deterministic demo query** — a PromQL literal expression that needs no
  scraped data at all; any running Prometheus answers it identically, so the real-cluster check
  needs zero scrape-target setup. · status: documented
- **The out-of-cluster controller can't reach a ClusterIP Service directly** — every other
  real-cluster check so far only needed the controller to reach the Kubernetes API server itself
  (which `kubeconfig` already routes to); reaching an arbitrary in-cluster Service (Prometheus)
  needed a `kubectl port-forward` to localhost first, the same way any out-of-cluster tool would.
  · status: documented
- **Metric evaluation is unconditional, unlike schedule-driven writes** — `BlackoutEvaluator`
  gates `status.currentProfile` and intent emission (both real shrink/grow-adjacent actions), but
  observing a live metric is passive telemetry, not an action, so it runs regardless of blackout.
  · status: documented


## Decision: metric evaluated synchronously inside the existing reconcile loop, not a dedicated poller

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/WardenPolicyReconciler.java`
- **why:** WardenPolicyReconciler already reconciles on a cadence (event-driven plus the 30s maxReconciliationInterval periodic resync from #69). Querying Prometheus synchronously each reconcile reuses that cadence directly instead of running a second background thread with its own timer and a cache the reconciler would need to read from - one clock, not two.
- **alternative:** Run a dedicated background poller thread on its own interval, caching the latest value for the reconciler to read - rejected: a second concurrent execution context and cache to keep in sync, for a problem the existing reconcile cadence already solves.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: Prometheus URL from a controller-wide env var, not a new CRD field

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/ControllerConfig.java`
- **why:** spec.guardrail.metric (W-301) is the PromQL query, but nothing says where Prometheus lives. A single controller-wide WARDEN_PROMETHEUS_URL matches the common single-Prometheus-per-cluster reality this roadmap's own examples already assume, and stays optional - unset means guardrail evaluation is skipped everywhere, the same safely-absent posture the roadmap states for CacheHook.
- **alternative:** Add spec.guardrail.prometheusUrl as a new CRD field so different policies could point at different Prometheus instances - rejected: no example or requirement anywhere in this repo needs multiple Prometheus instances, so this would be speculative generality.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: reuse Jackson (already transitively present) instead of a third hand-rolled JSON reader

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/metrics/PrometheusMetricSource.java`
- **why:** warden-controller already pulls jackson-databind 2.19.0 transitively via Fabric8's own client (confirmed against the real dependency tree). Declaring it as an explicit, version-pinned direct dependency is simpler than a third minimal JSON parser in this codebase's module ecosystem - unlike warden-agent/warden-controller's ResourceQuantity duplication (W-304), which existed specifically to avoid introducing a NEW inter-module dependency; here nothing new is being introduced, just using what is already on the classpath.
- **alternative:** Hand-roll another minimal JSON reader mirroring warden-agent's MinimalJson - rejected: MinimalJson exists for warden-agent's specific zero-runtime-dependency architecture, a constraint warden-controller (already depending on Fabric8, java-operator-sdk, cron-utils) does not share.
- **design:** ../design.md
- **trust:** ✓ verified
