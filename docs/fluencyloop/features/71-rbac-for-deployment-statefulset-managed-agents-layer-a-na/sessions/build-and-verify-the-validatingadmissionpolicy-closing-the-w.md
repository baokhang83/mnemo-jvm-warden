# Session: Build and verify the ValidatingAdmissionPolicy closing the write-side RBAC gap

- **intent:** Build and verify the ValidatingAdmissionPolicy closing the write-side RBAC gap
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

- **Admission control never sees reads** — verified directly against the Kubernetes docs' own
  explicit statement: `GET`/`LIST`/`WATCH` bypass the admission layer entirely, by design (not a
  gap in `ValidatingAdmissionPolicy` specifically — true of webhooks too). This is *why* the
  read-side RBAC exposure has no native fix at all, not just an inconvenient one. · status:
  documented
- **`admissionregistration.k8s.io`'s `operations` enum has no `PATCH` value** — a real API server
  rejected the first policy YAML with exactly this error. PATCH requests are categorized under
  `UPDATE` for admission-matching purposes; there is no separate `PATCH` operation type to match
  on. · status: documented
- **Every pod's default projected service-account token is bound to that specific pod** (the
  `BoundServiceAccountTokenVolume` feature, GA for a while now) — carries `pod-name`/`pod-uid` as
  extra claims (`request.userInfo.extra` in CEL) even when many pods share one ServiceAccount.
  This is what makes the per-replica identity check possible at all despite the shared
  ServiceAccount. · status: documented
- **`harness.CrossPodResizeAttempt` reuses `PodResizeClient` unmodified** — the only thing that
  changes between "legitimate self-resize" and "the attack this policy prevents" is *which pod
  name* the exact same production client is pointed at, run from a different pod's own token.
  · status: documented


## Decision: ValidatingAdmissionPolicy layered on RBAC, not a per-replica ServiceAccount

- **where:** `deploy/warden-resize-admission-policy.yaml`
- **why:** A Deployment/StatefulSet's single PodTemplateSpec means every replica shares one ServiceAccountName - there is no native way to give each replica a different one, which rules out per-pod Roles at the source. ValidatingAdmissionPolicy (GA since K8s 1.30, already implied by this project's 1.35+ requirement) lets the write path (pods/resize PATCH) be scoped to the calling token's own bound pod-name claim instead, closing the actually dangerous gap without needing per-replica identities at all.
- **alternative:** Provision a distinct ServiceAccount + Role per replica - rejected: structurally impossible for a native Deployment/StatefulSet, since spec.template.spec.serviceAccountName is one shared value for the whole template, not settable per-replica.
- **design:** ../design.md
- **trust:** ✓ verified

## Decision: accept that the read side cannot be narrowed natively, rather than keep searching for a workaround

- **where:** `docs/fluencyloop/features/71-rbac-for-deployment-statefulset-managed-agents-layer-a-na/design.md`
- **why:** Confirmed directly against Kubernetes' own documentation: admission control (both ValidatingAdmissionPolicy and webhooks) never intercepts GET/LIST/WATCH requests - reads bypass the admission layer entirely, by design. This is a structural fact about how Kubernetes authorizes requests, not a missing feature to work around, so continuing to search for a read-side fix would have been chasing something that doesn't exist.
- **alternative:** Keep looking for a mechanism to scope the GET pods read side (e.g. a hypothetical read-time admission hook, or hoping for an undocumented RBAC extension) - rejected: the docs' own explicit statement that admission never sees reads makes this a closed question, not an open one.
- **design:** ../design.md
- **trust:** ✓ verified

## Decision: ClusterRole + namespace RoleBinding instead of a Role defined directly in the manifest

- **where:** `deploy/wardenpolicy-demo-deployment.yaml.tmpl`
- **why:** A ClusterRole referenced by a RoleBinding in one namespace has identical effective scope to a Role defined directly there - no security difference. The only real difference is reuse: one ClusterRole definition, N RoleBindings across namespaces, relevant once a Helm chart (M6) installs this into more than one namespace.
- **alternative:** Keep the Role scoped directly in this one manifest, as #69 originally shipped it - rejected: would need redefining the identical rule in every namespace this workload is later installed into, for zero security benefit over the ClusterRole approach.
- **design:** ../design.md
- **trust:** ✓ verified
