# Session: Wire CacheHook.preWarm() into GrowSequence

- **intent:** Wire CacheHook.preWarm() into GrowSequence
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

- **`GrowSequence.growTo`** — now runs `resizeMemory` → `setSoftMax` → `preWarmCaches` (new); the kubelet-timeout-propagates-without-touching-SoftMax behavior from W-204 is unchanged, since the new step only runs after both prior steps succeed. · status: documented
- **`GrowSequence.cacheHooks`** — same shape as `ShrinkSequence`'s: a plain `Map<String, CacheHook>` constructor param, not a port, since there's no platform to abstract away, just app-owner callbacks. · status: documented
- **`GrowSequence.preWarmCaches`** — iterates the map, calls `preWarm()` per hook inside its own `try { } catch (RuntimeException e)`, logs by cache name via `AgentLog.info` on a throw, and always continues; `GrowOutcome` is still returned even if every hook throws, since grow has no verification gate to protect and preWarm is best-effort. · status: documented
- **`IntentWatcher.pollOnce`** — `CacheHookLookup.lookupAll(target.get())` is now hoisted above the shrink/grow branch and shared by both `ShrinkSequence` and `GrowSequence`, rather than looked up twice — same fresh-per-poll lookup W-502 already established. · status: documented
- **Timing source for "ahead of the predicted peak"** — no new scheduling code was needed here; W-304's `ScheduleEvaluator.currentProfileWithLeadTime` already advances the *profile* (and therefore the intent's `limitBytes`) during the `leadTime.warm` window, so `IntentWatcher` was already calling `GrowSequence.growTo` early — this slice only appends `preWarm()` onto that already-early call. · status: documented

---

## Decision: preWarm() runs last in GrowSequence, after SoftMax is raised

- **where:** `warden-agent/.../sequence/GrowSequence.java`
- **why:** a cache repopulating itself allocates heap; running preWarm() before SoftMax is raised risks those allocations getting evicted again by GC pressure honoring the still-low ceiling, defeating the point of pre-warming
- **alternative:** run preWarm() right after the cgroup resize but before setSoftMax — rejected: the heap ceiling would still reflect the old, smaller size while the cache tries to fill it
- **design:** ../design.md#sequence-one-grow-attempt
- **constitution:** §5
- **trust:** ✓ verified

## Decision: per-CacheHook try/catch isolation in GrowSequence.preWarmCaches, mirroring W-502

- **where:** `warden-agent/.../sequence/GrowSequence.java`
- **why:** an app owner's CacheHook is untrusted, third-party code from the agent's point of view, same as ShrinkSequence's flushEvictable() isolation; grow has no verification gate to protect either, so there's even less reason to let a warm-up bug fail an already-succeeded resize
- **alternative:** let a hook's exception propagate out of growTo — rejected: a single buggy app cache would turn a successful cgroup+SoftMax grow into a reported failure
- **design:** ../design.md#sequence-one-grow-attempt
- **constitution:** §12
- **trust:** ✓ verified
