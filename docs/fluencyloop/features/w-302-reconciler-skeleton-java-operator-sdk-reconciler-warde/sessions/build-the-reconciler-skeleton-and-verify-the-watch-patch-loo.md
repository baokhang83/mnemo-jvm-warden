# Session: Build the reconciler skeleton and verify the watch/patch loop against a real cluster

- **intent:** Build the reconciler skeleton and verify the watch/patch loop against a real cluster
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

- **`java-operator-sdk` reconciler wiring** — `@ControllerConfiguration` on a class implementing
  `Reconciler<T>`, registered via `new Operator().register(new XReconciler())` then
  `operator.start()`. No `prepareEventSources` override needed here since the reconciler watches
  only its primary resource type (`WardenPolicy`) and no secondary resources. · status: documented
- **`Config.autoConfigure(String context)`** — lets a Fabric8 client pick a specific kubeconfig
  context by name instead of relying on the ambient current-context. Not used in
  `WardenController` itself (production runs in-pod, where the default auto-detection finds the
  in-cluster service-account config) — only relevant for out-of-cluster tooling/verification.
  · status: follow-up (worth using explicitly if `verify-wardenpolicy-reconciler.sh` ever needs
  to run without mutating the ambient kubectl context)
- **`mvn dependency:build-classpath` needs the dependency already installed to the local repo**
  — running it for `warden-controller` alone failed until `warden-crd-model` was `mvn install`'d
  first; a same-reactor `-am` build isn't enough for this particular goal, since it resolves
  from the local/remote repository, not the in-memory reactor. · status: documented


## Decision: skeleton reconciler sets status.currentProfile to a deterministic placeholder, not real schedule evaluation

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/WardenPolicyReconciler.java`
- **why:** W-302's acceptance criteria says 'status reflects current profile,' but real profile selection is schedule-driven and the schedule evaluator doesn't exist yet (W-303). Picking the alphabetically-first key of spec.profiles is deterministic and proves the watch-then-patch loop works end-to-end, without guessing at schedule-evaluation logic that belongs to a different ticket.
- **alternative:** Leave status.currentProfile null until W-303 can compute a real value — rejected: it would leave the watch/patch wiring itself unproven (a null status doesn't distinguish 'reconciler never ran' from 'reconciler ran and correctly found nothing to report'), and the acceptance criteria explicitly wants status to reflect something.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: real-cluster verification of the reconcile/patch loop, not just a unit test of the selection logic

- **where:** `deploy/verify-wardenpolicy-reconciler.sh`
- **why:** WardenPolicyReconcilerTest proves placeholderCurrentProfile picks the right key, but the acceptance criteria is that a live WardenPolicy's status actually gets patched by a running controller against a real API server — the same constitution §8 distinction already applied to W-301's schema check.
- **alternative:** Rely on the unit test alone — rejected: it says nothing about whether java-operator-sdk's informer/reconcile/patch machinery is actually wired correctly end-to-end.
- **design:** ../design.md
- **constitution:** §8
- **trust:** ✓ verified
