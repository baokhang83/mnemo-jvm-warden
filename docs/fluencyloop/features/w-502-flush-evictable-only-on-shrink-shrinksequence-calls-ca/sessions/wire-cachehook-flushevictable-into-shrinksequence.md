# Session: Wire CacheHook.flushEvictable() into ShrinkSequence

- **intent:** Wire CacheHook.flushEvictable() into ShrinkSequence
- **started:** 2026-07-22

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

- **`ShrinkSequence.shrinkTo`** — now runs `setSoftMax` → `flushEvictableCaches` (new) → `deepGcAndUncommit` → RSS gate → resize, unchanged from W-203 except for the inserted flush step; the RSS gate and abort-without-touching-the-cgroup behavior are untouched. · status: documented
- **`ShrinkSequence.cacheHooks`** — a plain `Map<String, CacheHook>` constructor parameter, not a port like `HeapController`/`ResizePort`; there's no platform to abstract away here, just a set of app-owner callbacks discovered once per poll by the caller. · status: documented
- **`ShrinkSequence.flushEvictableCaches`** — iterates the map and calls `flushEvictable()` per hook inside its own `try { } catch (RuntimeException e)`; a throw is logged via `AgentLog.info` with the cache's registered name and the loop continues. Catches `RuntimeException`, not `Exception`, because `flushEvictable()` declares no checked exception — any checked failure or JMX connection problem on the MXBean proxy surfaces as an unchecked `UndeclaredThrowableException`, which is still a `RuntimeException`. · status: documented
- **`IntentWatcher.pollOnce`** — calls `CacheHookLookup.lookupAll(target.get())` fresh on every shrink branch (mirrors the existing "read the current limit fresh every poll, never cache it" pattern already used for `currentLimitBytes`), so a cache registered after agent startup is picked up on the very next poll without any restart. · status: documented
- **`ShrinkTrialDriver`** — the W-206 manual test-harness entry point got the same `CacheHookLookup.lookupAll(target)` wiring as `IntentWatcher`, since it constructs a real `ShrinkSequence` against a real attached target and would otherwise silently skip cache coordination during manual verification. · status: documented
- **W-503 boundary** — `GrowSequence` was deliberately left untouched; `preWarm()` wiring on the grow path is W-503's job, not this slice's — kept out per §1 (no speculative generality ahead of a story that actually needs it). · status: follow-up

---

## Decision: flushEvictable ordering enforced inside ShrinkSequence, not the caller

- **where:** `warden-agent/.../sequence/ShrinkSequence.java`
- **why:** constitution §5 requires shrink's ordering invariants enforced in code, not caller discipline; there are two real callers today (IntentWatcher, ShrinkTrialDriver) that would otherwise each need to remember to flush before calling shrinkTo, in the right spot
- **alternative:** call flushEvictable() from IntentWatcher before invoking shrinkTo — rejected: pushes an ordering guarantee onto every caller, exactly what §5 exists to prevent
- **design:** ../design.md#sequence-one-shrink-attempt
- **constitution:** §5
- **trust:** ✓ verified

## Decision: per-CacheHook try/catch isolation in ShrinkSequence.flushEvictableCaches

- **where:** `warden-agent/.../sequence/ShrinkSequence.java`
- **why:** an app owner's CacheHook is untrusted, third-party code from the agent's point of view; constitution §12 already requires independent concerns in one flow to have isolated failure blast radius, so one broken cache must not veto the shrink or block sibling hooks
- **alternative:** let a hook's exception propagate and abort the whole shrinkTo call — rejected: a single buggy app cache would silently disable Warden's memory reclamation for that pod
- **design:** ../design.md#sequence-one-shrink-attempt
- **constitution:** §12
- **trust:** ✓ verified
