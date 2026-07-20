# Session: Slice 2: fix cross-container cgroup resolution for real CI

- **intent:** Slice 2: fix cross-container cgroup resolution for real CI
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

- **Private vs. shared cgroup namespaces** — `/proc/<pid>/root/sys/fs/cgroup` is only already the target's own resolved cgroup when the target has its own *private* cgroup namespace (a real container boundary, verified via kind). With no container boundary at all (verified on a real GitHub Actions `ubuntu-latest` runner), crossing into `/proc/<pid>/root` only changes the mount namespace, not the cgroup one, so the process's actual cgroup subtree (three levels deep on that runner: `system.slice/hosted-compute-agent.service/`) has to be located and appended by hand. · status: documented
- **The `"/.."` escape marker in `/proc/<pid>/cgroup`** — the kernel's own documented signal that a process's cgroup lies outside the *reading* process's cgroup namespace. `RssReader.resolveCgroupRoot()` branches on this marker rather than assuming either topology, so it works correctly in both the real pod (private namespace) and the real CI runner (no namespace boundary). · status: documented
- **Diagnosed against the real failing environment, not guessed** — found via a temporary, since-removed `CgroupDiagnosticTest` pushed to the actual PR branch to dump the real runner's `/proc/self/cgroup` and `/sys/fs/cgroup` listing; the fix was verified against that same real failure before being finalized. · status: documented


## Decision: branch on the /.. escape marker, not on assumed pod-vs-bare-host topology

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/RssReader.java`
- **why:** the first version assumed every deployment has a private cgroup namespace, true for a sidecar pod and verified via kind, but a real CI run on a bare ubuntu-latest runner proved that assumption wrong since no container boundary means /proc/pid/root does not pre-resolve the cgroup; the fix detects which case applies at runtime via cgroup v2's own /.. escape marker in /proc/pid/cgroup rather than hardcoding one topology
- **alternative:** always append the /proc/pid/cgroup path onto /proc/pid/root/sys/fs/cgroup — rejected: verified against the real kind pod that this double-applies the path when the target already has a private cgroup namespace, since crossing /proc/pid/root already lands you in that namespace's own pre-resolved view
- **design:** ../design.md#sequence-read-reconcile-refuse-cleanly-on-cgroup-v1
- **constitution:** §8
- **trust:** ✓ verified
