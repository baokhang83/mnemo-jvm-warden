# Session: Slice 1: RssReader hostPath resolution + CgroupNotFoundException

- **intent:** Slice 1: RssReader hostPath resolution + CgroupNotFoundException
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

- **`RssReader.resolveCgroupRoot(pid, hostCgroupRoot)`** — reads `/proc/<pid>/cgroup` for the
  target, extracts the last path segment via `lastPathSegment(parseCgroupPath(...))` (the
  container-runtime scope name, e.g. `cri-containerd-<id>.scope` — the only part visible
  regardless of how deep cgroup namespacing hides the ancestry above it), then calls
  `searchForCgroupDirectory` to find it under a host-mounted cgroup root. Throws
  `CgroupNotFoundException` if nothing matches anywhere under the mount. Runs once per
  `forTarget()`, not per `currentRss()` — the resolved path is cached in the instance. ·
  status: documented
- **`RssReader.searchForCgroupDirectory(root, name)`** — a bounded-depth (`MAX_SEARCH_DEPTH =
  12`) `Files.walk` filtering for a directory whose filename exactly matches the scope name,
  sorted by path depth (`Path::getNameCount`) before taking the first match, so a
  shallower/more-specific hit wins over an accidental deeper match of the same name. Tolerates
  a missing root (returns `Optional.empty()` rather than throwing) so a misconfigured/missing
  `hostPath` mount surfaces as the intended `CgroupNotFoundException`, not a raw IOException. ·
  status: documented
- **`CgroupNotFoundException` vs. `UnsupportedCgroupVersionException`** — the former means
  "nothing under the host mount has this name at all" (near-certainly a missing/misconfigured
  `hostPath` mount — an operator-fixable deployment problem); the latter means "found the
  directory, but it has no `memory.current`" (genuinely cgroup v1 — nothing fixable short of
  upgrading the node). Kept as two distinct exception types so the error message points at the
  right remediation instead of a generic "couldn't read cgroup" for two very different causes. ·
  status: documented
- **Why the real-target test now points at `/sys/fs/cgroup` directly, not `HOST_CGROUP_ROOT`**
  — CI runs the spawned target JVM directly on the GitHub Actions runner, not inside a pod with
  the sidecar's `hostPath` mount, so `RssReaderTest.readsWorkingSetAndReconcilesNmtOnARealLinuxTarget`
  calls the package-private `resolveCgroupRoot(pid, Path.of("/sys/fs/cgroup"))` seam directly
  rather than the public `forTarget(AttachedJvm)` (which hardcodes `/host-cgroup`) — exercising
  the same search/read logic against a genuine cgroup tree without needing a cluster. · status:
  documented


## Decision: search by directory name under the host mount, not by computing the path

- **where:** `warden-agent/.../heap/RssReader.java (searchForCgroupDirectory)`
- **why:** cgroup namespacing hides the ancestry above the scope name from the target's own namespaced view, so the full absolute path can only be discovered by searching the host-mounted tree, not read or computed directly — and the exact hierarchy shape is cgroup-driver- and cluster-dependent, so matching by the one stable identifier (the scope name) generalizes where a hardcoded template would not
- **alternative:** reconstruct the path by template from known systemd-cgroup-driver conventions (kubelet.slice/kubelet-kubepods.slice/kubelet-kubepods-<qos>.slice/kubelet-kubepods-<qos>-pod<uid>.slice/<scope>) — rejected: breaks on a cgroupfs-driver cluster or any different kubelet slice naming scheme; verified real depth on kind/containerd is 5 levels but that's not guaranteed elsewhere
- **design:** ../design.md#sequence-resolve-via-host-mount-search-by-container-id-substring
- **constitution:** §9
- **trust:** ✓ verified

## Decision: two distinct exception types for not-found vs. found-but-wrong-version

- **where:** `warden-agent/.../heap/CgroupNotFoundException.java, RssReader.java`
- **why:** the two failure modes have completely different remediations — a missing hostPath mount is an operator-fixable deployment mistake, while cgroup v1 is a node-level constraint nothing in the pod spec can fix — so collapsing them into one exception would force whoever reads the log to re-derive which situation they're in from context that isn't in the message
- **alternative:** reuse UnsupportedCgroupVersionException for both cases (any failure to reach a usable memory.current) — rejected: its existing message ('this target is on cgroup v1') would actively mislead an operator whose real problem is a missing volume mount
- **trust:** ✓ verified
