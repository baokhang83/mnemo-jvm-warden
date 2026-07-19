# Session: Slice 3: AttachSupervisor + WardenAgent wiring

- **intent:** Slice 3: AttachSupervisor + WardenAgent wiring
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

- **`AttachSupervisor`** — a daemon thread that loops locate-then-attach, marks `HealthState` ready once attached, polls `AttachedJvm.isAlive()` on the same interval, and on death marks not-ready, closes, and falls back into the same locate-then-attach loop; `stop()` interrupts, joins with a timeout, marks not-ready, and closes any live attachment. · status: documented
- **`WardenAgent` wiring** — readiness is no longer set unconditionally at boot; `/readyz` now reflects `AttachSupervisor`'s real state, verified live by packaging the jar, pointing it at a spawned target JVM via `WARDEN_TARGET_PID`, and watching `/readyz` go 200 on attach and 503 within one poll cycle of the target being killed. · status: documented
- **`AgentLog`** — widened from package-private to public since `agent.attach` is now a second package that needs the shutdown-safe stdout logger; no behavior change. · status: documented


## Decision: one supervising loop for both first-attach and reconnect

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/attach/AttachSupervisor.java`
- **why:** treating 'attach at boot' and 'reattach after the target dies' as the same locate-then-attach loop means there is exactly one implementation of how to acquire a target to keep correct, instead of two that could quietly drift apart
- **alternative:** a separate exception/event-driven reconnect path triggered off RPC failures — rejected: conflates an RPC call failing with the target process being gone, and duplicates the attach logic in a second place
- **design:** ../design.md#sequence-attach-on-boot-reconnect-after-target-restart
- **constitution:** §6
- **trust:** ✓ verified
