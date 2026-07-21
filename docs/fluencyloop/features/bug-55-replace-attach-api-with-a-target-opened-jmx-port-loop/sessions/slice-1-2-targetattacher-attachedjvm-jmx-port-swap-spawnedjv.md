# Session: Slice 1-2: TargetAttacher/AttachedJvm JMX-port swap + SpawnedJvm fix

- **intent:** Slice 1-2: TargetAttacher/AttachedJvm JMX-port swap + SpawnedJvm fix
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

- **`TargetAttacher`** — connects via `JMXConnectorFactory.connect()` against `127.0.0.1:<configured port>` (`WARDEN_TARGET_JMX_PORT`, default 9999), rather than `VirtualMachine.attach()`. No longer throws `AttachNotSupportedException`; only `IOException` (connection failures) now. · status: documented
- **`AttachedJvm`** — dropped its `VirtualMachine` field entirely; `close()` only closes the `JMXConnector`. Public surface (`pid()`, `mbeanConnection()`, `isAlive()`, `close()`) unchanged, so every downstream class needed zero changes. · status: documented
- **`TargetLocator` and `AttachSupervisor`'s loop shape are both unaffected** — `TargetLocator` never crossed into the target's mount namespace (pure `/proc` PID enumeration), and `AttachSupervisor`'s locate-attach-monitor loop doesn't care how "attach" internally connects, only that it returns an `AttachedJvm` or throws `IOException`. · status: documented
- **`SpawnedJvm.awaitDescriptor()` timing bug** — only confirmed the target's process was visible via `/proc`, not that its JMX RMI registry had finished binding; a test calling `TargetAttacher.attach()` directly (no `AttachSupervisor` retry wrapper) hit a real "connection refused" race. Fixed by having `awaitDescriptor()` additionally poll-connect to the JMX port before returning. · status: documented


## Decision: extend SpawnedJvm.awaitDescriptor() to wait for the JMX port, not just process visibility

- **where:** `warden-agent/src/test/java/io/github/baokhang83/mnemo/warden/agent/testsupport/SpawnedJvm.java`
- **why:** a real test run hit connection refused because the spawned target's process was visible via /proc before its JMX RMI registry had actually finished binding the port; AttachSupervisor's own production retry loop absorbs this timing, but tests calling TargetAttacher.attach() directly, once, need the wait built into the shared helper instead
- **alternative:** add a retry loop to each individual test that calls TargetAttacher.attach() directly — rejected: duplicates the same wait-for-readiness logic across every call site instead of fixing it once in the shared helper that already exists for exactly this purpose
- **design:** ../design.md#sequence-connect-over-the-targets-own-jmx-port-loopback-only
- **trust:** ✓ verified
