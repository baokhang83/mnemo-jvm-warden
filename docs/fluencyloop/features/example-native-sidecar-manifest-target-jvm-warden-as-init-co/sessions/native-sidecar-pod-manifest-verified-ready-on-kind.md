# Session: Native-sidecar pod manifest, verified Ready on kind

- **intent:** Native-sidecar pod manifest, verified Ready on kind
- **started:** 2026-07-13

---

## Knowledge transfer

- **Native sidecar = `initContainer` + `restartPolicy: Always`** — a normal init container runs to
  completion before the app; adding `restartPolicy: Always` (K8s 1.28+/1.29-on-by-default, stable
  1.33) makes it a *sidecar*: it starts before the app containers, the kubelet waits for its
  readiness before starting them, it stays running for the pod's whole life, and it is stopped
  *after* the app on termination. This start-before / stop-after ordering is the K8s-level
  guarantee the agent's safety ordering (W-202) relies on. · status: documented
- **`deploy/example-sidecar.yaml`** — a Pod with `warden` (the sidecar, `/healthz` liveness +
  `/readyz` readiness, 64Mi request) and `app` (the target JVM). `shareProcessNamespace: true` is
  set as the Attach-API prerequisite for W-102. · status: documented
- **Target JVM via `jwebserver`** — `eclipse-temurin:21-jdk` running the JDK's Simple Web Server is
  a real long-running JVM with default GC (G1), so no custom app or extra image is needed to stand
  in for a workload. · status: documented
- **Verification detail: `2/2` Ready** — Kubernetes counts a native sidecar in the pod's ready
  container count, and its status shows `state=running` (not `terminated/completed` like a plain
  init container). That `2/2` + `init/warden ready=True started=True state=running` is the proof
  the sidecar pattern took effect. · status: documented

---

## Decision: Warden as a native sidecar (initContainer + restartPolicy: Always)

- **where:** `deploy/example-sidecar.yaml` (`initContainers[0].restartPolicy: Always`)
- **why:** The agent must be up before the app and outlive it to manage the target JVM safely for
  the app's whole life. A native sidecar gives exactly that start-before / stop-after ordering at
  the K8s level; a plain second `containers` entry starts in parallel and offers no ordering.
- **alternative:** A regular sidecar (extra `containers` entry) — rejected: no startup/shutdown
  ordering guarantee, and a never-exiting helper there can wedge pod/Job termination.
- **design:** [../design.md](../design.md) — the lifecycle sequence.
- **constitution:** §5-adjacent (the lifecycle underpinning the agent's safety ordering)
- **trust:** ✓ verified — applied to a kind cluster (K8s 1.36): pod `2/2 Running`,
  `init/warden ready=True started=True state=running`, `cont/app ready=True`.

## Decision: shareProcessNamespace enabled now, as documented intent

- **where:** `deploy/example-sidecar.yaml` (`spec.shareProcessNamespace: true`)
- **why:** The Attach API (W-102) needs the sidecar to see the target JVM's PID, which requires a
  shared process namespace. Setting it now, with a comment, makes the example a truthful "how
  Warden attaches" reference rather than one that would silently fail to attach later.
- **alternative:** Defer to W-102 — rejected: the manifest's stated purpose is to show how Warden
  attaches, so the prerequisite belongs here, documented, not omitted.
- **constitution:** §1 (a named, forward-looking inclusion — not silent speculation)
- **trust:** ✓ verified — the pod with `shareProcessNamespace: true` applies and both containers
  reach Ready (it doesn't break the no-op agent).

## Decision: imagePullPolicy IfNotPresent + stock jwebserver target

- **where:** `deploy/example-sidecar.yaml` (`warden.imagePullPolicy`, `app.command`)
- **why:** `IfNotPresent` lets one manifest serve both a local `kind load`-ed image and a real
  cluster pulling from GHCR. Using the JDK's `jwebserver` as the target avoids building/publishing
  a throwaway sample app just to have a JVM to sit beside.
- **alternative:** A custom sample-app image + `Always` pull — rejected: more to build and publish
  for an example, and `Always` would fail on kind where the image is loaded, not pullable.
- **constitution:** §1 (YAGNI)
- **trust:** ✓ verified — the `kind load`-ed image was used and the `jwebserver` app reached Ready.
