# Session: CI workflow + coverage aggregator module + README badges

- **intent:** CI workflow + coverage aggregator module + README badges
- **started:** 2026-07-13

---

## Knowledge transfer

- **`coverage` module** — a leaf module with no product code whose only job is to run
  `jacoco:report-aggregate`, merging the other modules' `jacoco.exec` into one project-wide
  report/csv. It lists the measured modules as dependencies because `report-aggregate` reads its
  own dependencies' exec data, and builds last. · status: documented
- **Parent `jacoco prepare-agent`** — inherited by every module; instruments tests so each writes
  `jacoco.exec`. Restricted to `io/github/baokhang83/**` so only Warden's code is measured. ·
  status: documented
- **`.github/workflows/build.yml`** — on push-to-main and every PR: checkout, JDK 21 Temurin
  (maven cache), `mvn -B verify`, generate the JaCoCo badge; publish report+badge to `gh-pages`
  only on push to main. Mirrors mnemo-cache. · status: documented
- **README badges** — build status (Actions) + coverage (gh-pages Pages URL). The coverage badge
  renders only after CI runs on main and GitHub Pages is enabled. · status: follow-up

---

## Decision: a dedicated `coverage` aggregator module

- **where:** `coverage/pom.xml`, parent `pom.xml` `<modules>`
- **why:** A single project-wide coverage number needs `report-aggregate`, which reads the exec
  data of *its own dependencies*. A parent can't depend on its children (cycle), so the aggregator
  is a downstream leaf module that lists every measured module as a dependency.
- **alternative:** Per-module badges, or no aggregation — rejected: the requirement is one
  coverage number for the project; per-module badges don't give that.
- **design:** [../design.md](../design.md) — the `coverage` node depending on the measured modules.
- **constitution:** §1 (a module with no product code is justified by the requirement, not
  speculative)
- **trust:** ✓ verified — `mvn verify` produced `coverage/target/site/jacoco-aggregate/jacoco.csv`
  at 85.0% (289/340 instructions).

## Decision: instrument only Warden's own packages

- **where:** parent `pom.xml` — `jacoco prepare-agent` `<includes>io/github/baokhang83/**`
- **why:** Running `mvn verify` on JDK 25 surfaced `IllegalClassFormatException`s: JaCoCo 0.8.12
  can't parse JDK-internal classes (`com.sun.net.httpserver.*`, `jdk.internal.net.http.*`) that the
  HTTP tests load. Restricting instrumentation to our packages removes the whole class of problem
  (JaCoCo never touches JDK code), is version-independent, and is also more correct — coverage
  should measure our code, not the JDK.
- **alternative 1:** Bump JaCoCo to chase JDK 25 support — rejected: uncertain/newer-than-needed,
  and CI runs JDK 21 anyway. **alternative 2:** Leave the noise — rejected: stack traces on every
  local `mvn verify` for JDK 25 contributors.
- **constitution:** §3 (measure the right thing cleanly)
- **trust:** ✓ verified — after the change, 0 instrumentation errors and the csv is unchanged (our
  classes only).

## Decision: publish to gh-pages only on push to main

- **where:** `.github/workflows/build.yml` (the publish step's `if:` guard)
- **why:** PRs must verify (build + test + badge) but must never write to `gh-pages`; a fork PR
  should not be able to publish. The badge reflects `main`.
- **alternative:** Publish on every run — rejected: least-privilege violation and a noisy,
  branch-dependent badge.
- **constitution:** §5-adjacent (least privilege)
- **trust:** ⚠ not independently verified — the guard is correct by inspection, but the gh-pages
  publish + Pages serving can only be confirmed once CI runs on main (needs Pages enabled and
  Actions write permission on the repo).
