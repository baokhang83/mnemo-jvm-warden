# Session: Slice 2: deploy manifest hostPath mount + real cluster verification

- **intent:** Slice 2: deploy manifest hostPath mount + real cluster verification
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

- **`example-sidecar.yaml`'s `host-cgroup` volume** — a pod-level `hostPath` volume of
  `/sys/fs/cgroup` (type `Directory`, so `kubectl apply` fails fast if the node path doesn't
  exist rather than silently mounting nothing), mounted read-only into the `warden` initContainer
  at `/host-cgroup` — must match `RssReader.HOST_CGROUP_ROOT` exactly, since nothing enforces
  that agreement except the comment on the mount. · status: documented
- **Real end-to-end verification on kind v1.36** — confirmed the manifest already has a genuine
  UID mismatch without needing to fabricate one: the `warden` image runs as UID 999 (its
  Dockerfile's `USER warden`), the `app` image (`eclipse-temurin:21-jdk`, no `USER` directive)
  runs as UID 0. Compiled a small harness (`TargetAttacher.attach(pid)` +
  `RssReader.forTarget(attached).currentRss()`) inside the live `warden` container against the
  real `app` container's pid (found via `shareProcessNamespace` + `/proc/<pid>/comm`), and cross-
  checked its output against the raw cgroup files read directly through the same mount at the
  same moment: `workingSetBytes` (88834048) = `cgroupMemoryCurrent` (88895488) −
  `inactive_file` (61440), exact arithmetic match. Real search depth observed: 5 levels
  (`kubelet.slice/kubelet-kubepods.slice/kubelet-kubepods-burstable.slice/kubelet-kubepods-burstable-pod<uid>.slice/<scope>.scope`),
  matching what design.md predicted from earlier spike work. · status: documented
- **PodSecurity Standards gap, surfaced but not resolved** — `hostPath` volumes are forbidden
  under both the `restricted` and `baseline` PodSecurity Standard profiles; this deployment has
  no verified answer for a cluster enforcing either beyond "the `warden` container needs an
  exemption." Documented as an open gap in `deploy/README.md` rather than silently ignored, since
  the kind cluster used for verification enforces neither profile by default so this was never
  actually exercised. · status: follow-up


## Decision: mount /sys/fs/cgroup at pod scope, not per-container, and only into warden

- **where:** `deploy/example-sidecar.yaml`
- **why:** the volume itself must be declared once at spec.volumes (Kubernetes has no per-container volume declaration), but only the warden container gets a volumeMounts entry for it — the app container has no legitimate need to see the node's cgroup tree, so it stays unmounted there, keeping the added exposure scoped to the one container that actually needs it
- **alternative:** none seriously considered — this follows directly from the hostPath tradeoff already weighed and accepted during design; the only real choices left at manifest-writing time were the mount path (/host-cgroup, chosen to match RssReader.HOST_CGROUP_ROOT and avoid colliding with the container's own real /sys/fs/cgroup) and volume type: Directory (fails fast on a misconfigured node rather than mounting an empty path silently)
- **design:** ../design.md
- **constitution:** §9
- **trust:** ✓ verified
