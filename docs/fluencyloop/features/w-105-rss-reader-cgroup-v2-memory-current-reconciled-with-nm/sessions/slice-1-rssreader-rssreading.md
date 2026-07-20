# Session: Slice 1: RssReader + RssReading

- **intent:** Slice 1: RssReader + RssReading
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

- **`RssReader`** — reads the target's cgroup v2 memory through `/proc/<pid>/root/sys/fs/cgroup` (injectable path seam for tests), rejects cgroup v1 by checking `memory.current` exists, computes `workingSetBytes = memory.current - inactive_file`, and best-effort reconciles NMT via `vmNativeMemory summary` over JMX. · status: documented
- **Cross-container cgroup access, verified on a real kind pod** — the agent's own `/sys/fs/cgroup` is a *different* cgroup than the target's, since they're separate containers in the same pod. `/proc/<target-pid>/root/sys/fs/cgroup/memory.current`, reachable through the shared PID namespace, correctly resolves into the target's own mount namespace: verified by inflating the target to ~83MB and reading the identical value both ways, while the sidecar's own cgroup stayed at ~1.6MB. · status: documented
- **Working set vs. raw memory.current, verified on the same real target** — `memory.current` read ~88MB, but `memory.stat` showed only 256KB was `anon` (true, non-reclaimable memory); the rest was `inactive_file` (reclaimable page cache from one file write). `memory.current - inactive_file` reuses the exact "working set" formula kubelet/cAdvisor use for eviction decisions. · status: documented
- **NMT as best-effort reconciliation, verified against a target without it enabled** — `vmNativeMemory summary` on a target launched without `-XX:NativeMemoryTracking` returns the plain string `"Native memory tracking is not enabled"`, not an error, so `RssReader` never requires it. · status: documented
- **Real-Linux-only test coverage** — `RssReaderTest`'s full end-to-end case is `@EnabledOnOs(OS.LINUX)` since it depends on a real cgroup v2 filesystem this dev machine (macOS) doesn't have; skips locally, runs for real on CI's `ubuntu-latest`. Verified it actually passes (not just compiles) by running the real test suite inside a Linux Docker container: 2/2 passed, 0 skipped. · status: documented


## Decision: /proc/<pid>/root/sys/fs/cgroup, not the agent's own cgroup

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/RssReader.java`
- **why:** the agent and target are separate containers in the same pod, so the agent's own /sys/fs/cgroup is a different cgroup entirely; verified on a real two-container kind pod that /proc/target-pid/root/sys/fs/cgroup correctly resolves into the target's own mount namespace and returns its real numbers
- **alternative:** read the agent's own /sys/fs/cgroup directly — rejected: verified it silently returns the wrong container's numbers, no error, just quietly incorrect data feeding a safety-critical decision
- **design:** ../design.md#class-diagram
- **trust:** ✓ verified

## Decision: working set (memory.current minus inactive_file), not raw memory.current

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/RssReader.java`
- **why:** verified on a real target that raw memory.current counted about 80MB of reclaimable page cache as used memory; subtracting inactive_file from memory.stat reuses the exact working-set formula kubelet already uses for eviction decisions, the same signal Kubernetes itself trusts for OOM risk
- **alternative:** raw memory.current as the gating number — rejected: would make an idle container with warm page cache look like it is under real memory pressure
- **design:** ../design.md#sequence-read-reconcile-refuse-cleanly-on-cgroup-v1
- **constitution:** §5
- **trust:** ✓ verified

## Decision: NMT reconciliation is best-effort, never required

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/RssReader.java`
- **why:** verified against a target launched without -XX:NativeMemoryTracking that vmNativeMemory summary returns the plain string 'Native memory tracking is not enabled' rather than an error, so RssReader treats NMT data as optional reconciliation, never a hard dependency
- **alternative:** require NMT to be enabled on every target — rejected: an operational burden most app teams would not opt into, for a diagnostic aid that is not load-bearing for the primary number
- **trust:** ✓ verified
