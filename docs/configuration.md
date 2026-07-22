# Configuration reference & operator runbook

Everything an operator can set on Warden, and what to do when it doesn't do what you
expect. Three layers, outside in:

1. [`WardenPolicy`](#wardenpolicy-spec-reference) — the declarative schedule you author, one per governed workload.
2. [`warden-agent`](#warden-agent-configuration) / [`warden-controller`](#warden-controller-configuration) — environment variables the sidecar and the cluster-wide controller read at startup.
3. [Helm chart values](#helm-chart-values) — the knobs `helm install` exposes over both of the above.

Then the [runbook](#runbook) — the failure modes you'll actually hit, in order of how often
they come up, each with what to check and what it means.

## `WardenPolicy` spec reference

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenPolicy
metadata:
  name: warden-policy-sample
spec:
  targetRef:
    apiVersion: v1
    kind: Pod
    name: warden-example
  timezone: Europe/Paris
  profiles:
    off-peak:
      request: 128Mi
      limit: 256Mi
    peak:
      request: 384Mi
      limit: 512Mi
  schedule:
    - cron: "0 22 * * *"
      profile: off-peak
    - cron: "0 7 * * *"
      profile: peak
  leadTime:
    shrink: 5m
    warm: 10m
  blackout:
    - start: "2026-12-24T00:00:00Z"
      end: "2026-12-26T00:00:00Z"
  guardrail:
    metric: 'sum(rate(http_requests_total[5m]))'
    shrinkBelow: "1"
    emergencyGrowAbove: "100"
```

### `spec.targetRef`

Which workload this policy governs — the same shape as an HPA's `scaleTargetRef`.

| Field | Type | Required | Notes |
|---|---|---|---|
| `apiVersion` | string | no | e.g. `v1`, `apps/v1` |
| `kind` | string | no | `Pod`, `Deployment`, or `StatefulSet` |
| `name` | string | no | name of the target object, in the same namespace as the `WardenPolicy` |

For a `Deployment`/`StatefulSet` target, the controller re-patches every pod it currently
owns on each reconcile (a periodic 30s resync — see [Timing](#timing-and-resync)), so a pod
created by a later rollout picks up the current intent without a dedicated watch per policy.

### `spec.timezone` — required

IANA zone id (e.g. `"Europe/Paris"`, `"UTC"`). The **only** field the API server rejects a
policy for omitting — every `cron` window in `spec.schedule` is evaluated in this zone,
DST-correctly, so a `22:00` window means 22:00 local time year-round, not a fixed UTC offset.

### `spec.profiles` — map of profile name → `ResourceProfile`

Named memory configurations that `schedule` and the lead-time machinery transition between.
Keys are arbitrary strings you choose (`off-peak`, `peak`, `demo-target`, ...) and referenced
by `schedule[].profile`.

| Field | Type | Required | Notes |
|---|---|---|---|
| `request` | quantity string | no | Kubernetes quantity, e.g. `"128Mi"` |
| `limit` | quantity string | no | Kubernetes quantity, e.g. `"256Mi"` |

### `spec.schedule` — list of `ScheduleWindow`

One entry per cron-defined transition. Order doesn't matter; at any instant, the window
whose cron expression most recently fired (in `spec.timezone`) wins.

| Field | Type | Required | Notes |
|---|---|---|---|
| `cron` | string | no | Standard 5-field cron syntax |
| `profile` | string | no | Must match a key in `spec.profiles` |

### `spec.leadTime`

How far ahead of a window's edge the transition actually fires, so the resize completes
*before* the traffic shift it's meant to be ready for — not on the same tick as the shift.

| Field | Type | Required | Notes |
|---|---|---|---|
| `shrink` | duration string | no | Fires at `window_start − shrink`, e.g. `"5m"` |
| `warm` | duration string | no | Fires at `peak_start − warm`, e.g. `"10m"` |

### `spec.blackout` — list of `BlackoutWindow`

A hard "do not touch" override. While `now` falls inside any blackout window, Warden holds
the pod at whatever profile it's already on — no scheduled transition, no emergency grow, no
shrink veto lookup. It beats every other signal (see [precedence](#precedence-blackout--metric--schedule)).

| Field | Type | Required | Notes |
|---|---|---|---|
| `start` | ISO-8601 timestamp | no | e.g. `"2026-12-24T00:00:00Z"` |
| `end` | ISO-8601 timestamp | no | e.g. `"2026-12-26T00:00:00Z"` |

### `spec.guardrail`

Live-metric thresholds evaluated against `metric`, a PromQL query the controller runs on the
same 30s resync as the schedule. Requires the controller's `WARDEN_PROMETHEUS_URL` to be set
([see below](#warden-controller-configuration)) — without it, `metric` is never evaluated and
both thresholds are inert.

| Field | Type | Required | Notes |
|---|---|---|---|
| `metric` | PromQL string | no | e.g. `'sum(rate(http_requests_total[5m]))'` |
| `shrinkBelow` | number string | no | A scheduled shrink is vetoed unless the metric reads *below* this |
| `emergencyGrowAbove` | number string | no | Forces an immediate grow — bypassing the schedule entirely for that reconcile — when the metric reads *above* this |

If the query fails, returns no series, or the controller has no Prometheus URL configured,
the metric reads as "unverified" — `shrinkBelow` then **vetoes** the shrink rather than
letting it through, and `emergencyGrowAbove` simply never fires. See
[Guardrail metric not evaluating](#guardrail-metric-not-evaluating-veto-stuck-on).

### `status` (read-only, set by the controller)

| Field | Type | Notes |
|---|---|---|
| `currentProfile` | string | The profile last resolved and patched to the target's intent annotation |
| `currentMetricValue` | number | The guardrail metric's last successful reading; absent if no guardrail is configured or the last evaluation failed |

### Precedence: blackout > metric > schedule

One deterministic rule, applied fresh every reconcile:

1. **Blacked out** → nothing changes. Neither the schedule nor an emergency grow is even
   consulted.
2. **Not blacked out, metric above `emergencyGrowAbove`** → grow to that profile immediately,
   skipping the schedule entirely for this reconcile.
3. **Not blacked out, no emergency grow, schedule has a candidate profile, metric not below
   `shrinkBelow` when the candidate is a shrink** → the shrink is **vetoed**; the pod stays on
   its current profile.
4. **Otherwise** → the schedule's candidate (adjusted for lead time) applies.

Metric *observation* itself is unconditional — it runs and updates `status.currentMetricValue`
even during a blackout — only the *actions* it can trigger are gated by precedence.

### Timing and resync

The controller reconciles a `WardenPolicy` on every spec/status change, and in any case at
least every **30 seconds** (`maxReconciliationInterval`) — this one clock is also what drives
the guardrail metric read and the `Deployment`/`StatefulSet` pod-annotation resync, so a pod
created by a later rollout picks up the current intent within 30s without a dedicated watch
per policy. `warden-agent` separately polls its own pod's intent annotation every
`WARDEN_INTENT_POLL_INTERVAL_SECONDS` (default 5s). A schedule transition can therefore lag
its cron boundary by up to ~30s on the controller side, plus up to the agent's poll interval
to actually reach the target — budget for this in `spec.leadTime` if precise timing matters.

## `warden-agent` configuration

Read from the process environment at startup (no config file). `WARDEN_POD_NAME` and
`WARDEN_TARGET_CONTAINER_NAME` have no default and fail the agent fast on startup if unset —
guessing wrong there means silently resizing nothing, or resizing the wrong container.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `WARDEN_POD_NAME` | **yes** | — | This pod's own name (wire it from the Downward API: `fieldRef: metadata.name`) |
| `WARDEN_TARGET_CONTAINER_NAME` | **yes** | — | Name of the sibling container the agent resizes |
| `WARDEN_HEALTH_PORT` | no | `8080` | Port for `/healthz`, `/readyz`, `/metrics` |
| `WARDEN_GC_TIMEOUT_SECONDS` | no | `30` | How long a shrink's deep-GC-and-uncommit step waits before giving up |
| `WARDEN_RESIZE_TIMEOUT_SECONDS` | no | `30` | How long a resize PATCH waits for kubelet confirmation |
| `WARDEN_INTENT_POLL_INTERVAL_SECONDS` | no | `5` | How often the agent re-reads its own pod's intent annotations |

The agent also requires a **read-only `hostPath` mount of `/sys/fs/cgroup`** at
`/host-cgroup` inside its container — this is how it reads the target's RSS and cgroup v2
memory files without a container-boundary-crossing hack. See
[Target's cgroup not found](#targets-cgroup-not-found) if this is missing.

## `warden-controller` configuration

| Variable | Required | Default | Notes |
|---|---|---|---|
| `WARDEN_PROMETHEUS_URL` | no | unset | Base URL of the Prometheus the controller queries for `spec.guardrail.metric`. Leave unset if no policy uses a guardrail — it's optional, not a hard dependency. |

## Helm chart values

The `warden` chart (`charts/warden`) deploys the cluster-wide controller and exposes the
sidecar as an includable named template (`warden.sidecar`) for an app's own chart to pull in.

```yaml
controller:
  image:
    repository: ghcr.io/baokhang83/mnemo-jvm-warden-controller
    tag: latest
    pullPolicy: IfNotPresent
  prometheusUrl: ""        # -> WARDEN_PROMETHEUS_URL
  resources:
    requests: { cpu: 100m, memory: 128Mi }
    limits: { memory: 256Mi }

sidecar:
  enabled: true             # master toggle an app chart gates its own include() on
  image:
    repository: ghcr.io/baokhang83/mnemo-jvm-warden
    tag: latest
    pullPolicy: IfNotPresent
  targetContainerName: app  # -> WARDEN_TARGET_CONTAINER_NAME, no default (same fail-fast posture)
  healthPort: 8080
  gc:
    timeoutSeconds: 30
  resize:
    timeoutSeconds: 30
  intentPollIntervalSeconds: 5
  resources:
    requests: { cpu: 25m, memory: 64Mi }
    limits: { memory: 256Mi }
```

`sidecar.*` are reference defaults for the `warden.sidecar` template — an app chart including
it supplies its own equivalent `cfg` dict following this same shape (see
`deploy/example-sidecar.yaml` for the hand-authored pattern the template reproduces).

## Runbook

### The agent's health & metrics endpoints

Every agent exposes three plain-text HTTP endpoints on `WARDEN_HEALTH_PORT` (default `8080`)
— start any investigation here:

| Endpoint | Meaning |
|---|---|
| `GET /healthz` | Always `200` if the process is up at all (liveness) |
| `GET /readyz` | `200` once attached to the target JVM, `503` otherwise (readiness) |
| `GET /metrics` | Prometheus text exposition — resize counts, aborts, RSS, GC stats, `warden_gc_supported` |

```
warden_resizes_total{direction="grow"}                 12
warden_resizes_total{direction="shrink"}                9
warden_aborts_total                                     1
warden_bytes_reclaimed_total                    134217728
warden_target_rss_working_set_bytes             219746304
warden_target_gc_collections_total{collector="G1 Young Generation"}   842
warden_target_gc_collection_time_seconds_total{collector="G1 Young Generation"} 3.271
warden_gc_supported{collector="G1"}                     1
```

### Agent stuck at `/readyz` → 503

The agent only marks itself ready once it has attached to the target JVM. Check the agent's
logs for the attach attempt. Common causes: `WARDEN_TARGET_CONTAINER_NAME` doesn't match an
actual sibling container name, or the target JVM hasn't started listening yet (the agent
retries with backoff — this is expected for the first several seconds of a pod's life).

### "Agent is read-only for this target" (GC support matrix)

Warden can only resize a JVM whose collector can **uncommit** freed pages back to the OS —
without that, lowering a soft heap ceiling would never actually shrink the process, so there's
nothing to gain. This is a permanent fact about the attach, resolved once and cached — not
re-checked or re-logged every poll tick.

| Collector | Runtime soft max | Uncommit | Warden support |
|---|---|---|---|
| ZGC | yes | yes | full (shrink + grow) |
| Shenandoah | yes | yes | full (shrink + grow) |
| G1 | no (uses periodic GC instead) | yes | full (shrink + grow) |
| Serial / Parallel / Epsilon / anything else | — | no | **read-only** |

**What you'll see:** a log line reading `target's collector (OTHER) does not support Warden
resize operations; agent is read-only for this target`, and `warden_gc_supported{collector="OTHER"}`
reading `0` on `/metrics`.

**What to do:** this isn't a bug — it's Warden correctly declining to do nothing useful.
Switch the target JVM to G1 (the default on modern JDKs), Shenandoah, or ZGC if you want
Warden to manage it; there's no configuration flag to force resize support onto an
incompatible collector.

### Shrink aborted by the verification gate

A shrink never lowers the cgroup limit until the target's RSS is *confirmed* below the new
limit, post-GC. If that verification fails, the cgroup limit is left untouched, `SoftMax` is
left lowered (advisory only — no OOM risk, and it gives the next attempt a head start), and
`warden_aborts_total` increments.

**What you'll see:** `warden_aborts_total` climbing on `/metrics`; no corresponding
`warden_resizes_total{direction="shrink"}` increment; the pod stays on its prior profile.

**What to do:** the target's live working set genuinely doesn't fit the requested profile
yet. Check `warden_target_rss_working_set_bytes` against the profile's `limit` — either the
profile is too small for real traffic, or app-level caches aren't shedding enough on
`CacheHook.flushEvictable()`. Increasing `spec.leadTime.shrink` gives the deep-GC step more
wall-clock room before the window edge, but won't help if the working set is simply larger
than the target profile.

### Target's cgroup not found

The agent reads RSS and cgroup limits through a bounded-depth search under a host-mounted
`/sys/fs/cgroup` view. Two distinct failures here mean two different things:

- **"could not find a cgroup directory ... anywhere under the host-mounted cgroup view; is
  the hostPath mount configured?"** — the agent's own container is missing the required
  read-only `hostPath` mount of `/sys/fs/cgroup`. Fixable: check the sidecar's pod spec (the
  Helm chart wires this automatically; a hand-authored manifest may not).
- **"is not on cgroup v2 (no memory.current); cgroup v1 is not supported"** — the node itself
  runs cgroup v1. Not fixable from the policy or the chart; Warden requires cgroup v2 on the
  node.

### Guardrail metric not evaluating (veto stuck on)

If `spec.guardrail.metric` is set but `status.currentMetricValue` never appears, or a
scheduled shrink stays vetoed indefinitely, check in order:

1. Is `WARDEN_PROMETHEUS_URL` set on the controller? Without it, the metric is never queried
   at all — this is the most common cause.
2. Does the PromQL in `metric` actually return a series? An empty result reads identically to
   a query failure — both leave the metric "unverified," and an unverified metric **vetoes**
   any shrink candidate rather than letting it through by default. This is deliberate: a
   guardrail that fails open would let a shrink proceed on missing data, exactly the failure
   mode a guardrail exists to prevent.
3. Check the controller's logs for `failed to evaluate guardrail metric for WardenPolicy
   .../...` — `status.currentProfile` still updates correctly even when metric evaluation
   fails; the two are evaluated independently per reconcile.

### Blackout not taking effect / taking effect at the wrong time

`spec.blackout[].start`/`end` are absolute ISO-8601 timestamps, evaluated in UTC regardless
of `spec.timezone` (unlike `schedule`, which is zone-aware). A blackout window authored in
local time needs its offset converted to `Z` explicitly.

### Intent not reaching the target pod

If `status.currentProfile` updates correctly but the target pod's actual resource limits
never change, check:

1. The controller's logs for `failed to emit intent for WardenPolicy .../... (status.currentProfile
   still updated)` — intent emission and status update are intentionally isolated from each
   other, so this failure is silent unless you check the logs.
2. `spec.targetRef` — a `name`/`kind` mismatch, or a target in the wrong namespace, means the
   controller has nothing to PATCH.
3. The agent's own `/readyz` — if the agent hasn't attached yet, it has nothing to act on even
   once it reads the correct intent annotation.
