# Session: Slice 2: TargetAttacher + AttachedJvm

- **intent:** Slice 2: TargetAttacher + AttachedJvm
- **started:** 2026-07-19

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

- **`VirtualMachine.startLocalManagementAgent()`** — starts (or reuses) a JMX connector on an already-running target and returns its service URL; requires nothing from the target's own launch flags, unlike the classic `-Dcom.sun.management.jmxremote` approach. · status: documented
- **`AttachedJvm`** — wraps the pid, the `VirtualMachine` handle, the `JMXConnector`, and the resulting `MBeanServerConnection`; `mbeanConnection()` is what later heap drivers and W-101's `GcDetector` read the target's MXBeans through, since `ManagementFactory.getPlatformMXBeans(connection, Type.class)` accepts any `MBeanServerConnection`, local or remote. · status: documented
- **`TargetAttacherTest`** — attaches to a real spawned child JVM (JEP 330 single-file source launch) rather than a mock, specifically so platform-level breakage (RMI/JMX bootstrap) surfaces in CI instead of being hidden behind a fake. · status: documented


## Decision: startLocalManagementAgent(), not target-side JMX flags

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/attach/TargetAttacher.java`
- **why:** the JDK 9+ Attach API can start a JMX connector on an already-running target and hand back its service URL, so nothing needs to be configured on the app's own launch command
- **alternative:** require the target to launch with -Dcom.sun.management.jmxremote.* — rejected: pushes JMX configuration onto app teams who don't control or know about Warden
- **design:** ../design.md#sequence-attach-on-boot-reconnect-after-target-restart
- **trust:** ⚠ not independently verified

## Decision: close() swallows an already-severed target connection instead of throwing

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/attach/AttachedJvm.java`
- **why:** the real-JVM test caught connector.close() throwing UnmarshalException once the target process had already been killed — exactly the state AttachSupervisor will clean up from on every reconnect, so treating peer-already-gone as success rather than a propagated failure is what makes the reconnect loop (W-102's acceptance criteria) actually work
- **alternative:** keep close() declaring throws IOException and have callers catch it — rejected: every caller would need the same ignore-if-already-dead logic, so the exception carries no actionable signal and should not leave this class
- **trust:** ✓ verified
