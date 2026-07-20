# Session: Slice 1: DeepGc + UncommitResult

- **intent:** Slice 1: DeepGc + UncommitResult
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

- **`DeepGc`** — forces `GC.run` (invoked over JMX as the zero-arg `gcRun` operation on `com.sun.management:type=DiagnosticCommand`, the remote equivalent of `jcmd <pid> GC.run`; `System.gc()` cannot be called on a remote process) then polls `MemoryMXBean.getHeapMemoryUsage().getCommitted()` for a stability signal. Not ZGC-specific — guarded by `GcCapabilities.supported()`, which is also true for G1. · status: documented
- **`ZUncommitDelay` gates uncommit independent of `GC.run`** — verified by spiking against a real target with a 20s delay: committed heap stayed exactly flat for the full 20+ seconds after `GC.run` ran, then began dropping only once the delay elapsed. Forcing a collection identifies *all* freeable memory in one pass; it does not shorten or bypass the collector's own per-region uncommit timer. Production default is 300 seconds. · status: documented
- **`SpawnedJvm.garbageChurner` + `awaitStdoutLine`** — extended the shared test helper with a target that allocates-then-discards a block and prints a marker, and a way to block on that marker; needed because guessing a fixed sleep for "child finished allocating" raced unpredictably against JVM startup/compile time. · status: documented


## Decision: guard on GcCapabilities.supported(), not collector == ZGC

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/DeepGc.java`
- **why:** the roadmap has W-106 (Shenandoah) and W-107 (G1) reuse this exact contract, and G1 genuinely uncommits via periodic GC despite having no soft max, so forTarget() checks supported() and rejects only OTHER
- **alternative:** mirror W-103's ZGC-only check — rejected: would incorrectly block G1, which this operation does support
- **design:** ../design.md#class-diagram
- **trust:** ✓ verified

## Decision: a stability poll must see a real drop before trusting unchanged as settled

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/DeepGc.java`
- **why:** the first version counted 3 consecutive unchanged committed-heap samples as completed, but caught by spiking against a real target: unchanged-because-settled and unchanged-because-the-delay-hasn't-elapsed-yet look identical, so it returned completed=true after ~750ms having observed zero actual change, long before real uncommit could have happened
- **alternative:** keep counting any N unchanged samples as stable — rejected: verified against a real target that this produces a false-positive completion, which is exactly the failure mode constitution section 5 (no unverified shrink) exists to prevent
- **design:** ../design.md#sequence-forces-a-collection-poll-until-committed-heap-stabilizes
- **constitution:** §7
- **trust:** ✓ verified
