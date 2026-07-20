# Session: Slice 1: lifecycle-check manifest + verify-lifecycle-ordering.sh

- **intent:** Slice 1: lifecycle-check manifest + verify-lifecycle-ordering.sh
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

- **`deploy/lifecycle-check.yaml`** — a minimal two-container pod (generic busybox, not the real Warden image) proving the native-sidecar start-before/stop-after guarantee itself, independent of anything agent-specific. · status: documented
- **`deploy/verify-lifecycle-ordering.sh`** — creates a throwaway kind cluster (or reuses one via `--cluster`), applies the manifest, asserts both orderings, tears down, exits non-zero on failure. Retries past a real observed race: a freshly created kind cluster's node reaches Ready before the `default` ServiceAccount exists, so the first `kubectl apply` can fail transiently. · status: documented
- **Log-follow-before-delete, not log-after-delete** — capturing the sidecar's SIGTERM-trap output requires starting `kubectl logs -f` *before* triggering `kubectl delete pod`, not after; a real timed test showed the pod can be fully garbage-collected within ~2-3s of deletion, and `kubectl logs` on a gone pod just errors "not found" — too fast a window to reliably poll after the fact. · status: documented
- **Filed [#55](https://github.com/baokhang83/mnemo-jvm-warden/issues/55)** — a real, unrelated, severity-worth-flagging bug found while setting this up: the shipped agent (non-root) never attaches to a target running as a different UID (root, the default). Confirmed root cause by matching UIDs and observing attach succeed. Out of scope for W-202 itself (this ticket doesn't depend on attach succeeding), so filed separately rather than folded in. · status: documented


## Decision: prove ordering via shared marker files (causality), not wall-clock timestamps

- **where:** `deploy/lifecycle-check.yaml`
- **why:** a first attempt compared container start/stop timestamps and hit two real problems: busybox's date has no sub-second precision, and even with precision, two containers starting in the same wall-clock second aren't reliably distinguishable that way; a marker file touched on start and removed right before exit lets the other container directly observe was-already-running / had-already-exited with no clock involved
- **alternative:** keep the timestamp approach and just improve precision, e.g. install coreutils for nanosecond date — rejected: still an indirect proxy for what actually needs proving, causal ordering, and adds a dependency to a container that's supposed to be minimal
- **design:** ../design.md#sequence-the-proof-start-to-stop
- **trust:** ✓ verified

## Decision: marker files on a shared volume, not a /proc/cmdline substring search

- **where:** `deploy/lifecycle-check.yaml`
- **why:** the first causality-proof implementation searched /proc/*/cmdline for a literal marker string identifying the other container's process, but reliably reported a false positive, caught by actually running it, because the search pattern was embedded in the same script doing the searching so grep matched its own /proc/self/cmdline entry; marker files on a shared emptyDir have no such self-reference risk since presence/absence is checked by a completely different process than the one that wrote them
- **alternative:** exclude self by PID when grepping /proc/*/cmdline — rejected: fixes the immediate false positive but keeps a fragile, harder-to-reason-about mechanism for something a plain file's existence answers directly
- **trust:** ✓ verified
