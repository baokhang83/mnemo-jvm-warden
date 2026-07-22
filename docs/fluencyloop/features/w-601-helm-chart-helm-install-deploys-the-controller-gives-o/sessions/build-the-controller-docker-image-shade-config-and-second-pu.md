# Session: Build the controller Docker image, shade config, and second publish job

- **intent:** Build the controller Docker image, shade config, and second publish job
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

- **`warden-controller`'s shaded jar** — unlike `warden-agent` (zero runtime deps), the controller pulls in fabric8 kubernetes-client, operator-framework, and cron-utils, so it needs an uber-jar to run as a plain `java -jar`. `maven-shade-plugin` is configured module-scoped (mirrors how `maven-jar-plugin`'s Main-Class manifest is scoped to `warden-agent` only), bound to the `package` phase, with `ServicesResourceTransformer` + `ManifestResourceTransformer`. Verified by actually running the shaded jar standalone and inside the built container (not just trusting the config), confirming no `ServiceConfigurationError` from fabric8's HTTP-client `ServiceLoader` selection. · status: documented
- **Pre-existing Docker build bug found and fixed** — the root `pom.xml` added `warden-cache-api` as a reactor module back in W-501, but neither `Dockerfile` nor the new `Dockerfile.controller` copied its `pom.xml`/`src` into the build stage, so `mvn -pl <module> -am` couldn't parse the reactor. Both agent and controller images have been failing to build since W-501 merged; nobody had rebuilt either image since, so it went unnoticed. Fixed on both Dockerfiles, verified with a real `docker build` of each. · status: documented
- **`dependency-reduced-pom.xml`** — a byproduct `maven-shade-plugin` writes to the module root (not `target/`), so it wasn't caught by the existing `target/` gitignore rule; added `dependency-reduced-pom.xml` to `.gitignore` directly. · status: documented

---

## Decision: shaded uber-jar for the controller, not a classpath assembled in the Dockerfile

- **where:** `warden-controller/pom.xml`
- **why:** the controller has a real runtime dependency graph (fabric8 kubernetes-client + operator-framework + cron-utils), unlike the agent; shading keeps the runtime Dockerfile stage identical in shape to the agent's (COPY jar + java -jar), rather than teaching the Dockerfile a dependency:copy-dependencies + classpath-construction step
- **alternative:** mvn dependency:copy-dependencies into target/lib/ and launch with java -cp app.jar:lib/* — rejected: pushes classpath assembly into the Dockerfile instead of the build, and loses the single-jar simplicity the agent's image already has
- **constitution:** §3
- **trust:** ✓ verified

## Decision: two independent Dockerfiles/publish jobs, not one parameterized Dockerfile

- **where:** `Dockerfile.controller, .github/workflows/publish.yml`
- **why:** the agent and controller are independently versioned, independently deployed artifacts (one runs per-pod as a sidecar for the app's whole life, one runs once cluster-wide) with genuinely different runtime shapes (plain jar vs. shaded jar) — two small, single-purpose Dockerfiles read clearly on their own
- **alternative:** one Dockerfile with a build-arg selecting which module's jar to package — rejected: couples two independently-evolving images' build logic into one conditional file for a small amount of file-count savings
- **constitution:** §3
- **trust:** ✓ verified
