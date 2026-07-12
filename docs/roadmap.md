# Roadmap

The roadmap is sliced so that the **safety-critical, differentiating core**
(making the JVM release memory, then resizing in a provably safe order) lands
first, and the convenience/operability layers build on top of it. Each story is
a vertical slice that ends in something demonstrable.

## Stack

- **Java 21** + **Maven** (consistent with mnemo-cache)
- **Fabric8 Kubernetes Client** + **java-operator-sdk** — CRD, informers,
  in-place `resize` subresource
- **JVM control** via the Attach API + `HotSpotDiagnosticMXBean` (SoftMaxHeapSize,
  `GC.run`, Native Memory Tracking) — no `jcmd` subprocess
- Sidecar optionally built as a **Quarkus / GraalVM native image** for a minimal
  footprint

## Milestones

| # | Milestone | Goal | Ships |
|---|---|---|---|
| **M0** | Foundations | Repo builds, publishes, and deploys as a no-op sidecar | image on GHCR, example manifest |
| **M1** | Heap Controller | The agent can *make a JVM release memory to the OS* | verified RSS drop, demoable |
| **M2** | Ordered Resize | The full shrink/grow handshake, OOMKill-safe | in-place resize with abort path |
| **M3** | Policy & Schedule | `WardenPolicy` CRD drives transitions on a timezone-aware calendar | declarative off-peak windows |
| **M4** | Guardrails | Live metrics can veto a shrink and force an emergency grow | schedule + metric precedence |
| **M5** | Cache Integration | Evictable-only flush + pre-warm; mnemo-cache adapter | stateful-node story works |
| **M6** | Distribution & Ops | Helm, self-telemetry, full GC matrix, docs | installable, observable v1 |
| **M7** | Predictive (v2) | Learned curves *propose* schedule edits; VPA coexistence | out of the safety path |

Legend for labels used below: `area/agent`, `area/controller`, `area/crd`,
`area/jvm`, `area/cache`, `area/build`, `area/docs`; `type/feature`,
`type/chore`, `type/test`; `safety/critical` (a defect can OOMKill or drop hot
state).

---

## M0 — Foundations

### W-001 — Maven multi-module skeleton
As a contributor, I want a clean module layout so components stay decoupled.
- Modules: `warden-agent`, `warden-controller`, `warden-crd-model`, `examples`.
- `mvn verify` green on a fresh checkout.
- Labels: `area/build`, `type/chore`.

### W-002 — Container build + GHCR publish pipeline
As a user, I want to pull a Warden image so I can run it.
- Multi-stage `Dockerfile` producing the agent image.
- GitHub Actions publishes `ghcr.io/baokhang83/mnemo-jvm-warden:<tag>` on release.
- Package is public; `docker pull` works unauthenticated.
- Labels: `area/build`, `type/chore`. Depends on: W-001.

### W-003 — Agent runtime skeleton
As an operator, I want the agent to start, read config, and report health.
- Loads a config file / env, exposes `/healthz` and `/readyz`.
- Runs as a no-op (no JVM control yet); clean startup/shutdown logs.
- Labels: `area/agent`, `type/feature`. Depends on: W-001.

### W-004 — CI: build, test, coverage
As a maintainer, I want CI to gate merges with coverage, mirroring mnemo-cache.
- Build + unit tests on PR; Jacoco coverage badge published.
- Labels: `area/build`, `type/chore`. Depends on: W-001.

### W-005 — Example native-sidecar manifest
As an evaluator, I want a runnable example showing how Warden attaches.
- `deploy/example-sidecar.yaml`: target JVM + Warden as an init container with
  `restartPolicy: Always`.
- Applies cleanly to a `kind` cluster; both containers Ready.
- Labels: `area/docs`, `type/feature`. Depends on: W-002, W-003.

---

## M1 — JVM Heap Controller

### W-101 — `HeapController` interface + GC capability detection
As the agent, I want a collector-agnostic contract so the safety logic stays GC-blind.
- Interface: `currentRss()`, `setSoftMax()`, `deepGcAndUncommit()`, `capabilities()`.
- Detects the target's collector (ZGC / Shenandoah / G1) and its uncommit support.
- Labels: `area/jvm`, `type/feature`. Depends on: W-003.

### W-102 — Attach to target JVM
As the agent, I want a live control channel to the app JVM in the same pod.
- Connect via Attach API / local JMX to the target PID.
- Reconnect on target restart; surfaced in `/readyz`.
- Labels: `area/jvm`, `type/feature`. Depends on: W-101.

### W-103 — ZGC driver: SoftMaxHeapSize get/set
As the agent, I want to lower/raise the ZGC soft ceiling at runtime.
- Read and set `SoftMaxHeapSize` via `HotSpotDiagnosticMXBean`.
- Rejects the operation cleanly if the target isn't running ZGC.
- Labels: `area/jvm`, `type/feature`, `safety/critical`. Depends on: W-102.

### W-104 — Deep GC + uncommit
As the agent, I want to force a GC and return freed pages to the OS.
- Trigger `GC.run`; wait for asynchronous uncommit to actually complete (with timeout).
- Report bytes uncommitted.
- Labels: `area/jvm`, `type/feature`, `safety/critical`. Depends on: W-103.

### W-105 — RSS reader (cgroup v2 + NMT)
As the agent, I want a trustworthy resident-set number to gate resizes on.
- Read cgroup v2 `memory.current`; reconcile with Native Memory Tracking.
- Refuses to operate on cgroup v1 (explicit unsupported error).
- Labels: `area/jvm`, `type/feature`, `safety/critical`. Depends on: W-102.

### W-106 — Shenandoah driver
Same contract as W-103/104 for Shenandoah (`ShenandoahUncommitDelay`).
- Labels: `area/jvm`, `type/feature`. Depends on: W-101, W-104.

### W-107 — G1 driver
G1 support via periodic-GC / uncommit knobs.
- Labels: `area/jvm`, `type/feature`. Depends on: W-101, W-104.

---

## M2 — Ordered Resize (the safety handshake)

### W-201 — In-place resize client
As the agent, I want to change a running Pod's request/limit without restart.
- Fabric8 call to the `resize` subresource; confirms the kubelet applied it.
- Labels: `area/agent`, `type/feature`, `safety/critical`. Depends on: W-005.

### W-202 — Native-sidecar lifecycle ordering
As the agent, I want to outlive the app so I can shrink/grow across its whole life.
- Verified start-before-app / stop-after-app via the init-container+`restartPolicy: Always` pattern.
- Labels: `area/agent`, `type/test`. Depends on: W-005.

### W-203 — Shrink sequence with the verification gate
As an operator, I want a shrink that can never OOMKill the pod.
- Order: lower SoftMax → deep GC + uncommit → **verify RSS < target** → cgroup down.
- The gate is mandatory; cgroup is never lowered on an unverified shrink.
- Labels: `area/agent`, `type/feature`, `safety/critical`. Depends on: W-104, W-105, W-201.

### W-204 — Grow sequence
As an operator, I want a grow that never allocates into non-existent headroom.
- Order: cgroup up **first** → then raise SoftMax.
- Labels: `area/agent`, `type/feature`, `safety/critical`. Depends on: W-103, W-201.

### W-205 — Abort path
As an operator, I want a rejected shrink to be a safe no-op.
- If RSS won't drop or load returns mid-shrink, restore SoftMax, leave cgroup untouched.
- Labels: `area/agent`, `type/feature`, `safety/critical`. Depends on: W-203.

### W-206 — OOMKill safety test harness
As a maintainer, I want automated proof the ordering holds under adversarial load.
- `kind`-based tests: inject load mid-shrink, assert no OOMKill and correct abort.
- Runs in CI (or nightly if slow).
- Labels: `area/agent`, `type/test`, `safety/critical`. Depends on: W-203, W-204, W-205.

---

## M3 — WardenPolicy CRD + static scheduler

### W-301 — `WardenPolicy` CRD model + validation
As an operator, I want a declarative policy object.
- Fabric8-generated types for `profiles`, `schedule`, `leadTime`, `guardrail`,
  `blackout`, `timezone`, `targetRef`.
- Schema validation rejects a policy with no `timezone`.
- Labels: `area/crd`, `type/feature`. Depends on: W-001.

### W-302 — Reconciler skeleton
As the controller, I want to watch `WardenPolicy` objects and hold desired state.
- java-operator-sdk reconciler; status reflects current profile.
- Labels: `area/controller`, `type/feature`. Depends on: W-301.

### W-303 — Cron schedule evaluation (timezone + DST aware)
As an operator, I want off-peak windows expressed in my business timezone.
- Evaluate `schedule` cron windows in the policy's zone; correct across DST shifts.
- Labels: `area/controller`, `type/feature`. Depends on: W-302.

### W-304 — Lead-time transition triggering
As an operator, I want transitions to start *ahead* of the window.
- Fire shrink at `window_start − leadTime.shrink`; warm at `peak_start − leadTime.warm`.
- Labels: `area/controller`, `type/feature`. Depends on: W-303.

### W-305 — Blackout windows
As an operator, I want a hard "do not touch" override for launches/holidays.
- Blackout beats both schedule and (later) metric signals.
- Labels: `area/controller`, `type/feature`, `safety/critical`. Depends on: W-303.

### W-306 — Intent → agent handoff
As the controller, I want to tell the agent which profile to target.
- Emit intent (pod annotation/status or direct channel); agent acts via M2 sequences.
- End-to-end: a scheduled window actually resizes a demo workload.
- Labels: `area/controller`, `area/agent`, `type/feature`. Depends on: W-203, W-304.

---

## M4 — Guardrails (live-truth override)

### W-401 — Prometheus metric source
As the controller, I want to read a live traffic signal.
- Evaluate a configured PromQL query on an interval; expose the latest value.
- Labels: `area/controller`, `type/feature`. Depends on: W-302.

### W-402 — `shrinkBelow` veto
As an operator, I want a scheduled shrink blocked if traffic isn't actually quiet.
- Metric ≥ `shrinkBelow` vetoes the shrink → routes to the abort path.
- Labels: `area/controller`, `type/feature`, `safety/critical`. Depends on: W-205, W-401.

### W-403 — `emergencyGrowAbove` reactive grow
As an operator, I want an off-schedule spike to trigger an immediate grow.
- Metric > `emergencyGrowAbove` grows now, bypassing the calendar (Scenario 2 insurance).
- Labels: `area/controller`, `type/feature`, `safety/critical`. Depends on: W-204, W-401.

### W-404 — Precedence engine
As an operator, I want one deterministic rule: **blackout > metric > schedule**.
- Unit-tested truth table over all three inputs.
- Labels: `area/controller`, `type/test`. Depends on: W-305, W-402, W-403.

---

## M5 — Cache Integration

### W-501 — `CacheHook` SPI
As an app owner, I want Warden to coordinate with my cache during resizes.
- SPI: `flushEvictable()`, `preWarm()`, `stats()`; optional and safely absent.
- Labels: `area/cache`, `type/feature`. Depends on: W-003.

### W-502 — Flush evictable-only on shrink
As an app owner, I want shrink to reclaim idle cache but never my hot set.
- Shrink flushes evictable/expired entries only; hot working set untouched.
- Labels: `area/cache`, `type/feature`, `safety/critical`. Depends on: W-203, W-501.

### W-503 — Pre-warm on grow
As an app owner, I want the cache warm before traffic returns.
- Grow triggers `preWarm()` ahead of the predicted peak (via leadTime).
- Labels: `area/cache`, `type/feature`. Depends on: W-204, W-304, W-501.

### W-504 — mnemo-cache reference adapter
As an evaluator, I want a working example of the stateful-node story.
- `CacheHook` implementation for mnemo-cache; end-to-end shrink/grow keeps hot state.
- Labels: `area/cache`, `type/feature`. Depends on: W-502, W-503.

---

## M6 — Distribution & Operability

### W-601 — Helm chart
As an operator, I want `helm install` to deploy the controller + inject the sidecar.
- `charts/warden` with values for image, GC, injection toggle.
- Labels: `area/build`, `type/feature`. Depends on: W-002, W-306.

### W-602 — Agent self-telemetry
As an SRE, I want to see what Warden did.
- Micrometer/Prometheus metrics: resizes, aborts, bytes reclaimed, RSS, GC pause.
- Labels: `area/agent`, `type/feature`. Depends on: W-203.

### W-603 — GC support matrix completion
As an operator, I want Warden to gracefully gate on unsupported collectors.
- ZGC/Shenandoah/G1 covered; unsupported GC → clear "read-only, won't resize" mode.
- Labels: `area/jvm`, `type/feature`. Depends on: W-106, W-107.

### W-604 — Docs: configuration reference + runbook
As an operator, I want a full `WardenPolicy` reference and an ops runbook.
- `docs/configuration.md` (every field) + failure/runbook section.
- Labels: `area/docs`, `type/chore`. Depends on: W-301.

### W-605 — Quarkus native-image sidecar (optional)
As an operator, I want a minimal-footprint agent.
- GraalVM native build; documented size/startup vs the JVM image.
- Labels: `area/build`, `type/chore`. Depends on: W-003.

---

## M7 — Predictive Scheduler (v2, out of the safety path)

### W-701 — Historical metric ingestion
Store observed traffic per workload for later modelling.
- Labels: `area/controller`, `type/feature`. Depends on: W-401.

### W-702 — Seasonality curve predictor
Derive a candidate off-peak/peak curve from history.
- Labels: `area/controller`, `type/feature`. Depends on: W-701.

### W-703 — Proposal + human-approval flow
The predictor *proposes* schedule edits; it never auto-applies to the safety path.
- "Your real off-peak looks like 21:30, not 22:00 — apply?" gated on approval.
- Labels: `area/controller`, `type/feature`, `safety/critical`. Depends on: W-702.

### W-704 — VPA-recommender coexistence mode
Emit JVM-aware recommendations *to* VPA instead of self-resizing.
- Makes Warden a drop-in enhancement to existing VPA deployments.
- Labels: `area/controller`, `type/feature`. Depends on: W-203.

---

## Critical path

The shortest line to the headline demo ("safely shrink a live JVM pod on a
schedule, save node cost") is:

```
W-001 → W-003 → W-101 → W-102 → W-103 → W-104 → W-105
      → W-201 → W-203 → W-205 → W-301 → W-303 → W-306
```

Everything else (guardrails, cache, Helm, predictor) hardens or extends that
spine.
