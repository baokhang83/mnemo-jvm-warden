# Session: Slice 1: TargetLocator finds the target PID

- **intent:** Slice 1: TargetLocator finds the target PID
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

- **`VirtualMachine.list()`** — enumerates every JVM visible to the calling process by probing the well-known per-JVM attach sockets (the same mechanism `jps`/`jcmd` use, not a network call); each result carries the target's PID (`id()`) and usually its main-class/jar (`displayName()`). Only sees other JVMs at all because `shareProcessNamespace: true` puts the target's process, and its attach socket, in the same namespace as the agent. · status: documented
- **`TargetLocator.findTarget()`** — excludes the agent's own PID (`ProcessHandle.current().pid()`) from the candidate list; picks the target only when exactly one other JVM remains, otherwise stays unattached (0 or 2+ candidates are both treated as "can't safely tell which one"). `WARDEN_TARGET_PID` overrides this for pods with more than one non-agent JVM. · status: documented


## Decision: exclude-self-and-require-exactly-one, not first-match or required-config

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/attach/TargetLocator.java`
- **why:** a normal sidecar pod has exactly one non-agent JVM, so identifying the target needs no operator-supplied config in the common case; but attaching heap control to the wrong process is worse than not attaching, so an ambiguous result (0 or 2+ candidates) deliberately stays unattached rather than guessing
- **alternative:** always require an explicit WARDEN_TARGET_PID — rejected: correct for the rare multi-JVM pod, but forces every ordinary deployment to wire a PID nobody wants to compute by hand
- **design:** ../design.md#class-diagram
- **trust:** ⚠ not independently verified
