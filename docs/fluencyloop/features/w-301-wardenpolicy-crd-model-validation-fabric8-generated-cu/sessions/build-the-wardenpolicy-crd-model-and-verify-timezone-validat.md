# Session: Build the WardenPolicy CRD model and verify timezone validation against a real cluster

- **intent:** Build the WardenPolicy CRD model and verify timezone validation against a real cluster
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

- **`crd-generator-maven-plugin`'s `generate` goal binds to `process-classes`, not `compile`** —
  it reflects on compiled `.class` files, so `mvn compile` alone does not produce the CRD YAML;
  `mvn process-classes` (or any later phase, like `test`/`package`) does. The generated file lands
  at `target/classes/META-INF/fabric8/wardenpolicies.warden.mnemo.io-v1.yml`. · status: documented
- **`@Required` on a `WardenPolicySpec` field, not on `WardenPolicy`'s own getters** — the
  documented getter-annotation mechanism is specifically for marking the top-level `spec`/
  `status` properties of the `CustomResource` subclass itself as required (not applicable here,
  since neither is optional); a required field *within* the spec is annotated directly, per
  Fabric8's own minimal example. · status: documented
- **`io.fabric8:kubernetes-client-api`, not the full `kubernetes-client`** — this module only
  needs the `CustomResource` base type and the `@Group`/`@Version` model annotations to declare
  the CRD, never an actual cluster connection (that's `warden-controller`'s job, when it exists)
  — the lighter `-api` artifact (no okhttp/vertx transport) keeps the module's footprint
  proportionate to what it actually does (§4). · status: documented


## Decision: field shapes for all seven WardenPolicySpec fields are derived from the tickets that already consume them, not invented fresh

- **where:** `warden-crd-model/src/main/java/io/github/baokhang83/mnemo/warden/crd/WardenPolicySpec.java`
- **why:** The issue names seven fields with no further detail. Every later M3/M4 ticket that reads one of them already states, in its own acceptance criteria, exactly what shape it needs (e.g. W-304 literally names leadTime.shrink and leadTime.warm) — deriving the model from those citations means W-302 onward gets real types to build against instead of a shape that might need reworking once the consuming ticket actually starts.
- **alternative:** Design a schema from first principles / general CRD conventions without cross-referencing the roadmap's own later tickets — rejected: it would very likely diverge from what those tickets actually need, forcing a rework once W-303/304/305 land.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: only timezone is validated in this slice; every other field is modeled but unvalidated

- **where:** `warden-crd-model/src/main/java/io/github/baokhang83/mnemo/warden/crd/WardenPolicySpec.java`
- **why:** W-301's acceptance criteria states exactly one validation rule (reject a policy with no timezone). Richer validation for the other fields — a schedule's cron syntax, a blackout window's date range — depends on logic that belongs to the tickets that actually evaluate them (W-303, W-305), which don't exist yet.
- **alternative:** Add speculative validation for the other fields now (e.g. a regex for cron strings) since the shapes are already known — rejected: that validation logic would be guessed at, disconnected from the actual evaluator that will need to agree with it, and is exactly the kind of generality this slice doesn't need yet.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: real-cluster verification, not just a unit test reading the generated YAML

- **where:** `deploy/verify-wardenpolicy-schema.sh`
- **why:** A unit test (CrdGenerationTest) proves the generator produces a schema requiring timezone, but the acceptance criterion is that the API server enforces it — the same constitution §8 distinction this repo has already hit twice (W-105's cgroup access, W-201's resize subresource): a schema that looks right on paper still needs to be applied to a real API server and shown to actually reject a bad object.
- **alternative:** Rely on the unit test alone, treating 'the generated YAML contains required: timezone' as sufficient proof — rejected: that only proves the generator's output, not that a real Kubernetes API server actually honors it.
- **design:** ../design.md
- **constitution:** §8
- **trust:** ✓ verified
