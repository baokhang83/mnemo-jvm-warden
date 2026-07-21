# Session: Build the intent handoff and verify a scheduled window resizes a real workload end-to-end

- **intent:** Build the intent handoff and verify a scheduled window resizes a real workload end-to-end
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

- **`Resource<T>.edit(UnaryOperator<T>)`** — Fabric8's GET-then-PATCH pattern, verified against
  the `EditReplacePatchable` interface source at the pinned tag (7.3.1), not assumed. Used to
  PATCH the target pod's annotations without clobbering the rest of its metadata. · status:
  documented
- **Widening `MinimalJson` and `ResourceQuantity` to `public`** — both were package-private,
  scoped to one existing use each (`PodResizeClient`, `ScheduleEvaluator`/`BlackoutEvaluator`).
  Reusing them from a new same-module package (`intent`) only needed visibility widening, not a
  second implementation — a different situation from the warden-agent/warden-controller
  cross-*module* duplication W-304 chose deliberately. · status: documented
- **The API server normalizes an exact-`Mi` byte count back to `"150Mi"` in status**, not the raw
  byte string PATCHed — already documented in `PodResizeClient`'s own javadoc from W-201, but
  re-caught live when `verify-wardenpolicy-intent.sh`'s first run asserted the wrong string and
  failed despite the actual resize having succeeded correctly (`SHRINK_TRIAL_RESULT`-equivalent
  log showed `Completed`). · status: documented
- **Self-stabilizing direction detection** — `IntentWatcher` compares intent to the container's
  *actual current* limit (read fresh every poll), not a cached "last applied" value: once a
  resize succeeds, the actual limit matches the intent, so the next poll's comparison is
  naturally a no-op, and a failed/aborted attempt is retried automatically without any extra
  state to keep in sync. · status: documented


## Decision: pod annotations as the intent transport, not a direct network channel

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/intent/IntentEmitter.java`
- **why:** Both the controller (Fabric8 informers) and the agent (PodResizeClient's HttpClient) already talk only to the API server. Reading/writing the target pod's own object keeps that the single communication path, rather than opening pod-to-pod networking, which would need service discovery and wouldn't survive a pod IP changing across a restart the way reading your own pod object trivially does.
- **alternative:** A direct HTTP channel from controller to agent — rejected: needs pod-IP service discovery and a second network path alongside the one that already exists.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: annotations carry resolved byte values, not the profile name

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/intent/IntentEmitter.java`
- **why:** The agent only ever needs two numbers to call ShrinkSequence/GrowSequence. Encoding the profile name instead would mean the agent has to resolve it against spec.profiles, which means depending on warden-crd-model - which warden-agent's own pom.xml explicitly says it doesn't. The controller resolves profile to bytes once (it already depends on warden-crd-model); the agent stays exactly as CRD-ignorant as it already is.
- **alternative:** Annotate with the profile name and let the agent fetch/resolve it — rejected: requires a new warden-agent to warden-crd-model dependency the project has avoided everywhere else.
- **design:** ../design.md
- **constitution:** §2
- **trust:** ✓ verified

## Decision: targetRef scoped to kind: Pod only this slice, with a tracked follow-up for Deployment/StatefulSet

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/intent/IntentEmitter.java`
- **why:** Every existing example targets a pod directly. Resolving a Deployment/StatefulSet targetRef to its live pod(s) via label selectors raises real, undesigned questions (which pods get the annotation? how does a rolling update interact with per-pod annotation?) that nothing has asked for yet.
- **alternative:** Design and build Deployment/StatefulSet resolution as part of this same slice — rejected: substantially larger, undesigned scope; tracked instead as issue #69 so it isn't lost.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: intent emission failures are isolated from status.currentProfile patching

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/WardenPolicyReconciler.java`
- **why:** A real cluster run caught this directly: a policy targeting a pod absent from that test's cluster threw out of IntentEmitter.emit(), which propagated out of the reconcile lambda entirely, so status.currentProfile silently never got patched either - two independent concerns failing atomically together for no reason. Wrapping intent emission in its own try/catch means a PATCH failure (missing pod, transient API error) can never prevent status from reflecting the schedule's actual decision.
- **alternative:** Leave the two writes coupled in one un-isolated call chain — rejected: directly caused a real, observed failure during W-306's own verification, where the entire reconcile silently failed to update status for an unrelated reason.
- **design:** ../design.md
- **constitution:** §12
- **trust:** ✓ verified
