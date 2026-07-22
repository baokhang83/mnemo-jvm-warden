# Session: Chart skeleton: controller Deployment/RBAC, values, and the warden.sidecar library chart

- **intent:** Chart skeleton: controller Deployment/RBAC, values, and the warden.sidecar library chart
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

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`controller-rbac.yaml`'s ClusterRole** — a cluster-wide Role, not namespaced: `WardenPolicyReconciler`'s informer (java-operator-sdk) watches `WardenPolicy` across every namespace with no per-policy scoping configured. Rules are read directly off what the code demonstrably does: `get/list/watch` on `wardenpolicies`, `get/patch` on `wardenpolicies/status` only (no finalizer is added — `WardenPolicyReconciler` doesn't implement `Cleaner` — so no `update` on the main resource), `get` on `deployments`/`statefulsets` (`IntentEmitter.resolvePodNames` only ever reads by name), and `get/list/patch` on `pods` (`IntentEmitter.annotate`'s PATCH, distinct from the sidecar's own namespaced `pods/resize` Role in `deploy/example-sidecar.yaml`). · status: documented
- **`controller-deployment.yaml`'s hardcoded `replicas: 1`** — deliberately not a `values.yaml` knob: with no leader election wired up, a second replica would double-reconcile every `WardenPolicy` independently (duplicate intent PATCHes, duplicate status writes) rather than standing by idle. · status: documented
- **`warden.sidecar` named template (`_helpers.tpl` → later moved to `charts/warden-sidecar`)** — renders only the `initContainer` entry reproducing `deploy/example-sidecar.yaml`'s shape; deliberately does not set `shareProcessNamespace: true` or mount the host-cgroup volume itself, since a single Helm `include` can only be dropped into one location in someone else's YAML — the calling chart sets those itself and includes the separate `warden.sidecar.volumes` template for the volume entry. `targetContainerName` and `resources` are `required` (Helm's `required` function, fails the render with a message) with no default, mirroring `AgentConfig`'s own fail-fast posture for the same field. · status: documented
- **Consumer-chart verification, not just review** — built and rendered a throwaway app chart (scratchpad, not committed) that actually included `charts/warden` as a Helm dependency to reach `warden.sidecar`, and observed it also rendered a second controller `Deployment`/`ClusterRole`/`ClusterRoleBinding` alongside the app's own `Pod` — a real defect a template-only review would have missed, since `warden.sidecar` renders correctly in isolation and only breaks compositionally. Re-verified the fix (library-chart split, next decision) the same way: rendering the same throwaway consumer chart against `charts/warden-sidecar` instead shows only the `Pod`, no controller resources. · status: documented

---

## Decision: split the reusable sidecar template into its own library chart (charts/warden-sidecar), separate from the application chart (charts/warden)

- **where:** `charts/warden-sidecar/ (new, type: library), charts/warden/templates/_helpers.tpl`
- **why:** an operator's own app chart needs some way to pull in warden.sidecar without hand-copying it, but Helm's only mechanism for one chart to see another chart's named templates is the dependency graph — and depending on charts/warden directly (a type: application chart) also deploys the whole controller (Deployment + ClusterRole/ClusterRoleBinding) a second time for every app that does it, which was verified empirically, not assumed. A Helm library chart (type: library) is structurally forbidden from rendering any resources of its own when used as a dependency, so moving warden.sidecar/warden.sidecar.volumes into charts/warden-sidecar closes this off by construction rather than by a documented convention (e.g. a controller.enabled flag someone has to remember to set)
- **alternative:** keep one chart (charts/warden) and add a controller.enabled values toggle, documenting that consumers depending on it for the sidecar template must set it to false — rejected: relies on every consumer remembering a manual opt-out rather than a structural guarantee, and doesn't reduce the actual coupling (the app chart is still depending on the whole controller chart just to reach one template)
- **design:** ../design.md#sequence-two-installs-two-lifecycles
- **constitution:** §2
- **trust:** ✓ verified
