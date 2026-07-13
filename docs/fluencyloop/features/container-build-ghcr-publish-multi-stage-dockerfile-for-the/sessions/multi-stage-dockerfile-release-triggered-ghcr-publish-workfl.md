# Session: Multi-stage Dockerfile + release-triggered GHCR publish workflow

- **intent:** Multi-stage Dockerfile + release-triggered GHCR publish workflow
- **started:** 2026-07-13

---

## Knowledge transfer

- **Multi-stage `Dockerfile`** — stage 1 (`maven:3.9-eclipse-temurin-21`) builds the agent jar and
  is discarded; stage 2 (`eclipse-temurin:21-jdk`) `COPY --from=builder` grabs only the jar, so the
  shipped image carries no Maven, source, or `.m2` cache. Runs as a non-root `warden` user. ·
  status: documented
- **Builder command** — `mvn -pl warden-agent -am -DskipTests package` builds just the agent and
  its upstream (the parent); the other module POMs are copied so the reactor loads, but their
  sources are not needed. · status: documented
- **`publish.yml`** — on `release: published`, logs into `ghcr.io` with `GITHUB_TOKEN`
  (`packages: write`) and pushes `:<release-tag>` + `:latest` via `docker/build-push-action`. ·
  status: documented
- **Health-probe startup warmup** — for the first ~1s after container start, connections can fail
  (000) on both endpoints before the server is warm; this is the connection/port layer, not the
  agent, and Kubernetes probe retries (`failureThreshold`/`periodSeconds`) tolerate it by design. ·
  status: documented

---

## Decision: JDK base image now, defer jlink/distroless to W-605

- **where:** `Dockerfile` (`FROM eclipse-temurin:21-jdk` runtime stage)
- **why:** The agent needs the Attach API (`jdk.attach`) to control the target JVM in W-102 — the
  very next M1 slice. A JRE has no `jdk.attach`, so it would be a false economy reverted
  immediately. Trimming to a jlink/distroless runtime (~90 MB) is a real win but is a size
  optimization that belongs in its own slice.
- **alternative 1:** `temurin:21-jre` — rejected: no Attach API. **alternative 2:** jlink now —
  rejected: premature optimization smuggled into the pipeline slice (§1); it deserves its own
  focused change (W-605).
- **design:** [../design.md](../design.md) — the base-image decision table.
- **constitution:** §4 (lean agent) / §1 (YAGNI) — correct floor now, deliberate trim later.
- **trust:** ✓ verified — image builds and `docker run` serves the probes; **size 744 MB**, the
  JDK cost W-605 will address.

## Decision: build the jar inside Docker (self-contained image)

- **where:** `Dockerfile` builder stage
- **why:** A multi-stage build that compiles from source makes the image reproducible from the repo
  alone — no dependency on a prior CI `mvn package` step or a passed-in artifact.
- **alternative:** `COPY` a CI-built jar into a single-stage image — rejected: couples the image to
  an external build step and a jar that isn't in the build context.
- **constitution:** §3 (self-contained, legible build)
- **trust:** ✓ verified — the builder's exact `mvn` command produces
  `warden-agent-0.1.0-SNAPSHOT.jar` at the path the runtime stage copies; image runs.

## Decision: publish on release with least privilege

- **where:** `.github/workflows/publish.yml`
- **why:** Images are versioned artifacts, so the trigger is `release: published` (tagging
  `:<tag>` + `:latest`), not every push. The workflow requests only `contents: read` +
  `packages: write` — nothing more.
- **alternative:** Publish on every push to main — rejected: mints images from unreleased commits
  and over-broad; violates least privilege.
- **constitution:** §5-adjacent (least privilege)
- **trust:** ⚠ not independently verified — the publish path (login + push to GHCR, making the
  package public) can only run when a real GitHub release is cut; verified by inspection only.
