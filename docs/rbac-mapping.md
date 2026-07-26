# RBAC-to-code-path mapping

Every Kubernetes verb Warden's own manifests grant, mapped to the exact code
path that requires it. If a verb here isn't backed by a real caller, remove
the grant — this table is the source of truth for "is this permission still
needed," not just documentation of what happens to be in the YAML today.

See [`threat-model.md`](threat-model.md) for the abuse cases these
boundaries defend against, and
[`../deploy/verify-rbac-boundaries.sh`](../deploy/verify-rbac-boundaries.sh)
for the automated `kubectl auth can-i` checks that prove these grants (and
only these grants) actually hold against a real API server.

## `warden-controller` (`ClusterRole`, cluster-wide)

Source: `charts/warden/templates/controller-rbac.yaml`.

| API group | Resource | Verbs | Scope | Code path | Why |
| --- | --- | --- | --- | --- | --- |
| `warden.mnemo.io` | `wardenpolicies` | `get`, `list`, `watch` | cluster-wide | `WardenPolicyReconciler`'s java-operator-sdk informer | The informer watches every `WardenPolicy` in the cluster; no per-namespace scoping is configured on the operator side, so a `Role` can't express this — it must be a `ClusterRole`. |
| `warden.mnemo.io` | `wardenpolicies/status` | `get`, `patch` | cluster-wide | `WardenPolicyReconciler.reconcile` → `UpdateControl.patchStatus` | `reconcile()` only ever patches the status subresource. No finalizer is registered (`WardenPolicyReconciler` doesn't implement `Cleaner`), so `update`/`patch` on the main `wardenpolicies` resource is never needed. |
| `apps` | `deployments`, `statefulsets` | `get` | cluster-wide (namespace-filtered at call time via `inNamespace`) | `IntentEmitter.resolvePodNames` | Read-only lookup of a `targetRef`'s `spec.selector`. Never created, updated, or deleted by the controller — no `create`/`update`/`delete`/`patch` verb is granted. |
| `""` (core) | `pods` | `get`, `list`, `patch` | cluster-wide | `IntentEmitter.podNamesForSelector` (list, by label selector) + `IntentEmitter.annotate` (get/patch, via `client.pods()...edit(...)`) | Lists a `Deployment`/`StatefulSet`'s live pods by selector, then PATCHes each one's annotations. **Distinct from `pods/resize`** — the controller never resizes a pod itself, only annotates it; see the sidecar's own `Role` below for the actual resize grant. |

Notable *absences* (no grant exists, and no code path needs one):
`secrets` (any verb), `pods/exec`, `pods/delete`/`delete` on any workload
resource, anything under the `certificates.k8s.io`/`rbac.authorization.k8s.io`
groups, and any `nodes` verb.

## Sidecar agent (`Role`, namespaced, scoped to one pod)

Source: `deploy/example-sidecar.yaml` (the pattern every
`Pod`-targeting deployment should follow; see the note on
`Deployment`/`StatefulSet` targets below).

| API group | Resource | Verbs | Scope | Code path | Why |
| --- | --- | --- | --- | --- | --- |
| `""` (core) | `pods/resize` | `patch` | `resourceNames: [<this pod>]` | `PodResizeClient.resizeMemory` → `patch()` | The actual in-place resize PATCH. Scoped by `resourceNames` to the sidecar's own pod — the narrowest grant Kubernetes RBAC can express for a statically-named pod. |
| `""` (core) | `pods` | `get` | `resourceNames: [<this pod>]` | `PodResizeClient.awaitConfirmation`/`matchesDesired` (poll `status`) + `PodIntentReader.read` (own annotations + target container's live limit) | Both the resize-confirmation poll and the intent-annotation read only ever fetch the agent's own pod object. |

**`Deployment`/`StatefulSet` targets can't use `resourceNames`:** replica pod
names are generated dynamically, so a static allow-list can't be provisioned
ahead of time. That shape instead uses a `ClusterRole` granting
`get`/`patch` on **every pod in the namespace**, bound via a namespace-scoped
`RoleBinding` (see `deploy/wardenpolicy-demo-deployment.yaml.tmpl` and
`deploy/README.md`'s "real, explicit RBAC tradeoff" section). The write side
of that broader grant (`pods/resize` PATCH) is narrowed back down by
`warden-resize-admission-policy.yaml` (#71) — see `threat-model.md`'s
"Resize API abuse" section. The read side (`get`/`list`) has no equivalent
narrowing available: Kubernetes admission control never intercepts reads.

## Verifying this table stays accurate

Run [`../deploy/verify-rbac-boundaries.sh`](../deploy/verify-rbac-boundaries.sh)
against a real (kind) cluster after any RBAC manifest change. It asserts,
via `kubectl auth can-i --as=<service-account>`, both:

- every verb/resource pair in the two tables above is **allowed**, and
- a representative set of verbs/resources **not** in either table — `secrets`
  get/list, `pods/exec` create, workload `delete`, and any `nodes` verb — is
  **denied** for both service accounts.
