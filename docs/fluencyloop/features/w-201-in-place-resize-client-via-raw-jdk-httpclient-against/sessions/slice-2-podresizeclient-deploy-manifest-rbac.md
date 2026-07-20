# Session: Slice 2: PodResizeClient + deploy manifest RBAC

- **intent:** Slice 2: PodResizeClient + deploy manifest RBAC
- **started:** 2026-07-20

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

- **`MinimalJson`** — a ~150-line hand-rolled recursive-descent JSON reader, chosen over a JSON library after weighing it against the earlier Fabric8 decision; parses into plain Map/List/String/Double/Boolean, enough to navigate `status.containerStatuses[].resources` and nothing more. · status: documented
- **`PodResizeClient`** — PATCHes `.../resize` with a memory-only `strategic-merge-patch+json` body, then polls GET on the pod until `status.containerStatuses[containerName].resources` matches the desired byte counts (via `K8sQuantity`) or `ResizeTimeoutException` fires. Real convergence observed at 366ms against a real kind cluster. · status: documented
- **End-to-end verification, not just unit tests** — built a throwaway `VerifyResize` harness, compiled against the real `warden-agent` classes (targeting Java 21 bytecode explicitly, since the local `javac` defaults to a newer release), copied into a running pod's sidecar container via `kubectl cp`, and ran it for real: confirmed the resize applied (`restartCount: 0`, exact byte-for-byte match), and confirmed RBAC's `resourceNames` scoping actually blocks resizing a different pod (403, not just documented intent). · status: documented
- **`deploy/example-sidecar.yaml`** — gained a `ServiceAccount`/`Role`/`RoleBinding` (patch on `pods/resize`, get on `pods`, both scoped by `resourceNames` to the one pod) and `resizePolicy: memory NotRequired` on the `app` container, needed for the resize subresource to be usable at all from this manifest. · status: documented


## Decision: bounds-check every JSON character read, route through peek()/next()

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/resize/MinimalJson.java`
- **why:** a test feeding truncated JSON, no closing brace, caught raw source.charAt(pos++) calls throwing an unchecked StringIndexOutOfBoundsException instead of the intended IllegalArgumentException, since the delimiter-reading code after a value assumed there was always another character to read
- **alternative:** leave the raw charAt calls and let StringIndexOutOfBoundsException propagate — rejected: an unchecked exception type for malformed or truncated input is a worse contract for callers than a documented IllegalArgumentException, and the fix, one bounds-checked next() used everywhere, is barely more code
- **trust:** ✓ verified

## Decision: scope RBAC by resourceNames to the pod's own name, not just resource type

- **where:** `deploy/example-sidecar.yaml`
- **why:** verified live against a real kind cluster that the same PodResizeClient code, run from inside the sidecar, gets a clean 403 when targeting a different pod name; without resourceNames scoping, a compromised sidecar's ServiceAccount could resize any pod in the namespace, not just its own
- **alternative:** grant patch/get on pods and pods/resize namespace-wide, relying on the fact that only the sidecar's own code ever calls resizeMemory() — rejected: RBAC is the actual security boundary, not caller discipline; scoping it as tightly as the real need costs nothing and shrinks the blast radius of a compromised sidecar
- **trust:** ✓ verified
