# Session: Scaffold parent + four modules building green

- **intent:** Scaffold parent + four modules building green
- **started:** 2026-07-12

---

## Knowledge transfer

- **Aggregator parent POM** (`pom.xml`) — `packaging: pom`; lists the four modules and builds
  them in dependency order (the reactor). Centralises the two things a skeleton should own: the
  Java target (`maven.compiler.release=21`) and dependency versions (`dependencyManagement`), so
  child modules declare dependencies without versions. · status: documented
- **Custom Resource Definition (CRD) / operator** — a CRD teaches the Kubernetes API server a
  new resource type (`WardenPolicy`), stored and served like a built-in; an operator is a
  reconciliation loop that drives actual state toward the desired state expressed by those
  resources. `warden-crd-model` is the Java schema of that type; `warden-controller` is the
  operator that consumes it. · status: documented
- **Module boundaries** — `warden-crd-model` (CRD types, no internal deps), `warden-controller`
  (operator, depends on crd-model), `warden-agent` (safety-critical sidecar, standalone),
  `examples` (placeholder). · status: documented

---

## Decision: warden-agent has no dependency on warden-crd-model

- **where:** `warden-agent/pom.xml` (absence of a crd-model dependency)
- **why:** The controller interprets the `WardenPolicy` and hands the agent a simple
  instruction (a target profile, later via a pod annotation). The agent only needs to execute
  intent, so it never touches the CRD types — keeping the sidecar lean and free of the
  CRD/Fabric8 machinery it would otherwise inherit transitively.
- **alternative:** Have the agent depend on `warden-crd-model` and read `WardenPolicy` directly
  — rejected: couples the lean sidecar to a module it doesn't use and drags CRD dependencies
  into the smallest, most safety-sensitive component.
- **design:** [../design.md](../design.md) — the amber "open" edge in the module graph, resolved
  to no edge.
- **constitution:** §4 (agent earns its footprint), §2 (abstractions at the seams), §1 (YAGNI)
- **trust:** ✓ verified — confirmed live with the maintainer after teaching what a CRD is;
  realized in the POM and `mvn verify` is green.

## Decision: exactly four modules + a smoke test each — no shared module

- **where:** `pom.xml` `<modules>`, and `**/SmokeTest.java` per module
- **why:** Build precisely the four modules the issue names, with one trivial smoke test each so
  "green" actually exercises compile + JUnit wiring in every module. No speculative
  `warden-common` / `test-support` module until a second consumer needs it.
- **alternative:** Introduce a shared `warden-common` (or a test-support module) up front —
  rejected: no code needs it yet; it would be an empty abstraction paid for now and maybe never
  used.
- **constitution:** §1 (YAGNI)
- **trust:** ✓ verified — `mvn verify` builds all five reactor entries green.

## Decision: groupId io.github.baokhang83.mnemo, matching the sibling repo

- **where:** `pom.xml` (parent `groupId`) and Java packages `io.github.baokhang83.mnemo.warden.*`
- **why:** `io.github.<user>` is a namespace provably owned via the GitHub account and
  publishable to Maven Central; it matches `mnemo-cache` (`io.github.baokhang83.mnemo`,
  package `...mnemo.cache`), so the two products share one coordinate space.
- **alternative:** A vanity `dev.mnemo.warden` — rejected: it claims a domain that isn't
  demonstrably owned, and diverges from the sibling module's established convention.
- **trust:** ✓ verified — checked against `mnemo-cache/pom.xml` and its source tree.
