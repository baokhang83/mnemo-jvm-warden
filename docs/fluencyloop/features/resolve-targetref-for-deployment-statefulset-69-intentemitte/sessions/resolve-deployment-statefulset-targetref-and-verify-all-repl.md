# Session: Resolve Deployment/StatefulSet targetRef and verify all replicas resize end-to-end

- **intent:** Resolve Deployment/StatefulSet targetRef and verify all replicas resize end-to-end
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

- **`Filterable.withLabelSelector(LabelSelector)`** — verified against the actual `Filterable`
  interface source at the pinned tag (7.3.1): accepts a `LabelSelector` object directly (read
  straight off a `Deployment`/`StatefulSet`'s own `spec.selector`), not just the `Map<String,
  String>` convenience overload. · status: documented
- **macOS's default `/bin/bash` is 3.2** (no associative arrays — `declare -A` fails outright) —
  caught directly when `verify-wardenpolicy-intent.sh`'s first Deployment-scenario run failed at
  that exact line. Every replica shares one identical `PodTemplateSpec`, so a single shared "before"
  value (not a per-pod map) was sufficient anyway. · status: documented
- **No agent-side code changed at all for #69** — `IntentWatcher`/`PodIntentReader` already only
  ever read their own pod's own annotations (W-306); resolving `targetRef` to N pods instead of 1
  is entirely a controller-side concern. · status: documented


## Decision: annotate every live replica, not a subset

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/intent/IntentEmitter.java`
- **why:** A Deployment/StatefulSet's replicas are interchangeable - the schedule applies to the workload as a whole, not a subset of it. Annotating every pod matching the workload's own selector identically means each replica's own agent independently converges to the same target, with zero agent-side awareness of whether it's part of a fleet.
- **alternative:** Annotate only a subset (e.g. one canary replica) - rejected: nothing about WardenPolicy's model expresses a canary/partial-rollout concept, and it would leave some replicas at a stale memory footprint with no defined signal for when the rest should follow.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: 30s periodic reconciliation resync catches rollout pods, not a per-policy dynamic secondary watch

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/WardenPolicyReconciler.java`
- **why:** A pod from a later rollout won't have the intent annotation until something re-triggers reconcile(). Watching Pods as a secondary resource needs a label selector known statically at controller startup, but each WardenPolicy's target selector is only known once its own targetRef is resolved - dynamic, per instance. @MaxReconciliationInterval's built-in periodic resync solves this at the right proportion (well within the schedule's own minute-level cron grain) without a custom SecondaryToPrimaryMapper.
- **alternative:** Build a per-policy dynamic secondary watch with a custom SecondaryToPrimaryMapper for near-instant catch-up - rejected: real, non-trivial machinery for a staleness window the schedule's own granularity already tolerates.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: pod annotations stay the transport - not the Deployment/StatefulSet pod template

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/intent/IntentEmitter.java`
- **why:** Annotating the pod template so new pods inherit it at creation was considered, since it would solve the rollout catch-up problem for free. But Kubernetes does not retroactively push a template annotation change to already-running pods - only a new rollout applies it, which would force every replica to restart just to deliver an annotation. That directly contradicts Warden's entire premise (in-place resize, no restart, warm pods).
- **alternative:** Annotate the pod template instead of individual pods - rejected: would force a full rolling restart on every schedule transition, the exact cost Warden exists to avoid.
- **design:** ../design.md
- **trust:** ✓ verified

## Decision: the Deployment RBAC gap is flagged and filed, not solved inline

- **where:** `deploy/wardenpolicy-demo-deployment.yaml.tmpl`
- **why:** Every Pod-targeting example scopes RBAC to one static, pre-known pod name via resourceNames - least privilege. A Deployment/StatefulSet's replica names are dynamic, so that pattern can't be provisioned ahead of time; the demo grants get/patch on every pod in the namespace instead, a real widening from least-privilege that deserves its own design rather than a default accepted in passing.
- **alternative:** Design and implement a narrower RBAC mechanism (e.g. some selector-scoped grant) as part of this same ticket - rejected: real, separate work with its own tradeoffs to weigh; tracked instead as issue #71 so it isn't lost or silently normalized as acceptable.
- **design:** ../design.md
- **trust:** ✓ verified
