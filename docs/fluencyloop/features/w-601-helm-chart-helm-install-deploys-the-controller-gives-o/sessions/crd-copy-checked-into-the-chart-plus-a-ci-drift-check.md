# Session: CRD copy checked into the chart plus a CI drift check

- **intent:** CRD copy checked into the chart plus a CI drift check
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

- **Helm's `crds/` directory** — a chart-root folder (sibling to `templates/`), installed once by `helm install`, with no Go-template substitution applied to its contents and never touched again by `helm upgrade`. This is why `charts/warden/crds/wardenpolicy-crd.yaml` is a literal, final YAML file rather than a template: CRDs are cluster-scoped and shared across every release, so letting `helm upgrade` rewrite one would risk silently invalidating custom resources other releases depend on. · status: documented
- **CRD drift check (`.github/workflows/build.yml`)** — after `mvn verify` (which runs `warden-crd-model`'s `crd-generator-maven-plugin` at the `process-classes` phase), a new CI step does `diff -u charts/warden/crds/wardenpolicy-crd.yaml` against the freshly regenerated `warden-crd-model/target/classes/META-INF/fabric8/wardenpolicies.warden.mnemo.io-v1.yml`, failing the build on any mismatch. Verified both directions manually: a clean `mvn -pl warden-crd-model process-classes` reproduces the checked-in file exactly (proves the copy is truly derived, not hand-typed), and an injected line into the checked-in copy makes `diff` exit 1 (proves the check actually catches drift, not just present in name). · status: documented

---

## Decision: checked-in CRD copy with a CI diff check, not a hand-maintained or templated CRD

- **where:** `charts/warden/crds/wardenpolicy-crd.yaml, .github/workflows/build.yml`
- **why:** the real source of truth for the CRD schema is the Java annotations in warden-crd-model, already covered by CrdGenerationTest.java; if that model changes (e.g. a field becomes required) without the chart's copy being updated, the chart would silently ship a schema that disagrees with the code reading it — a CI diff against the freshly generated file forces the two to stay in lockstep automatically, rather than relying on someone remembering to update the chart by hand
- **alternative:** hand-maintain the CRD YAML directly in the chart with no generation link — rejected: nothing would ever catch drift between the Java model and the shipped schema, and Helm's crds/ directory can't be templated anyway so there's no way to parameterize it into staying in sync
- **constitution:** §7
- **trust:** ✓ verified
