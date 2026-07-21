# Deploy examples

## `example-sidecar.yaml` — Warden as a native sidecar

A pod with two containers: a target JVM (`app`) and the Warden agent (`warden`) running as a
**native sidecar** — an `initContainer` with `restartPolicy: Always`, which starts before the app,
runs for the pod's whole life, and stops last (Kubernetes 1.29+).

Also includes a `ServiceAccount`/`Role`/`RoleBinding` granting the sidecar the least-privilege
RBAC it needs to resize its own `app` container in place (`patch` on `pods/resize`, `get` on
`pods`, both scoped by `resourceNames` to this one pod) — the in-place resize subresource is GA
from Kubernetes 1.35 (verified against a real 1.36 kind cluster; requires the
`InPlacePodVerticalScaling` feature gate on earlier versions).

### Run it on kind

```bash
# 1. Create a throwaway cluster
kind create cluster --name warden-demo

# 2. Make the agent image available to the cluster.
#    Once a release is published this is just a pull from GHCR; until then, build + load locally:
docker build -t ghcr.io/baokhang83/mnemo-jvm-warden:latest .
kind load docker-image ghcr.io/baokhang83/mnemo-jvm-warden:latest --name warden-demo

# 3. Apply and wait
kubectl apply -f deploy/example-sidecar.yaml
kubectl wait --for=condition=Ready pod/warden-example --timeout=120s

# 4. Look around
kubectl get pod warden-example                 # both containers Ready (2/2 incl. the sidecar)
kubectl logs warden-example -c warden          # agent lifecycle logs

# 5. Tear down
kind delete cluster --name warden-demo
```

The `warden` container serves `/healthz` (liveness) and `/readyz` (readiness) on port 8080; the
`app` container is the JDK's Simple Web Server on port 8000, standing in for a real workload.

### The `app` container's `-XX:+UseG1GC` flag is explicit, not assumed

Verified against a real target (via `jcmd <pid> VM.flags`) that at this container's memory size
(512Mi limit), the JVM's own default-collector ergonomics actually pick **Serial GC**, not G1 —
`Collector.OTHER`, which `AttachedHeapController` (W-203) correctly refuses to manage. `-XX:+UseG1GC`
is set explicitly on the `app` container's launch command so this example always demonstrates a
collector Warden can actually shrink, rather than silently depending on a default that only holds
at larger heap sizes.

### The `app` container's JMX flags are required

The agent connects to the target over a JMX port the target opens at launch, not the JDK Attach
API — see `TargetAttacher`'s javadoc for why (bug
[#55](https://github.com/baokhang83/mnemo-jvm-warden/issues/55): the Attach API turned out to
require the agent to run as real root to reach a target under a different UID, which is the
common case). Any target JVM Warden manages needs, at minimum:

```
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.rmi.port=9999
-Dcom.sun.management.jmxremote.host=127.0.0.1
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
-Djava.rmi.server.hostname=127.0.0.1
```

`jmxremote.host=127.0.0.1` is not optional: without it, this port is reachable from **any pod in
the cluster**, not just this one — verified directly with a real second pod completing a TCP
connect to the target's pod IP. Combined with `authenticate=false`, that would be unauthenticated,
cluster-reachable MBean access. With the loopback binding, `authenticate=false` is safe because the
port is provably unreachable outside this pod's own network namespace (re-verified on the same
live deployment).

The port number just has to match `WARDEN_TARGET_JMX_PORT` on the `warden` container (default
`9999`, so unset is fine if you use the default here too).

### The `warden` container's cgroup hostPath mount is required, and costs real privilege

`RssReader` (what M2's shrink-verify step reads to confirm a resize actually freed memory) needs
the target's cgroup files — `memory.current` and `memory.stat` — but the agent and target are
separate containers, so the agent's own `/sys/fs/cgroup` is the wrong cgroup. Reaching the
target's cgroup by crossing into its mount namespace via `/proc/<pid>/root` hit the same
UID-gated restriction that broke the Attach API (bug
[#55](https://github.com/baokhang83/mnemo-jvm-warden/issues/55)) — confirmed `Permission denied`
under a genuine UID mismatch, with no capability grant able to fix it (bug
[#57](https://github.com/baokhang83/mnemo-jvm-warden/issues/57)). Unlike #55, there is no
network-based escape hatch here: the JMX metrics that would have avoided a new mount
(`OperatingSystemMXBean.getTotalMemorySize()`/`getFreeMemorySize()`) track raw `memory.current`,
not the working-set-minus-reclaimable-cache number this class exists to compute — adopting them
would have silently regressed that safety property, so they were rejected.

The verified fix instead mounts `/sys/fs/cgroup` from the node into the `warden` container,
read-only, at `/host-cgroup`:

```yaml
volumes:
  - name: host-cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
containers: # (on the warden initContainer)
  - volumeMounts:
      - name: host-cgroup
        mountPath: /host-cgroup
        readOnly: true
```

**This is a real, explicit cost, not a free fix.** Cgroup namespacing hides a container's real
absolute path from its own namespaced view, so there is no Kubernetes-native way to scope a
`hostPath` mount to just this one pod's cgroup subtree — that path isn't known until the
container runtime assigns it, after scheduling. The mount therefore gives the sidecar read
visibility into **every cgroup on the node**, not just this pod's. No narrower alternative was
found; this tradeoff was weighed explicitly and accepted rather than left implicit. `RssReader`
finds the target's specific directory by a bounded-depth search under the mount for one named
after the container-runtime scope name reported in `/proc/<pid>/cgroup` (verified real depth on
kind/containerd: 5 levels — the search doesn't assume that exact shape, since the cgroup driver
and naming vary by cluster).

If your platform's PodSecurity Standards restrict `hostPath` volumes (the `restricted` and
`baseline` profiles both forbid them), this deployment needs an exemption for the `warden`
container — there is currently no verified way around that requirement.

## `lifecycle-check.yaml` — native-sidecar start/stop ordering (W-202)

Verifies, against a real cluster, that an `initContainer` with `restartPolicy: Always` actually
starts before the main container and stops after it — the guarantee `example-sidecar.yaml`
depends on. Uses minimal generic containers rather than the real agent image, since this is a
Kubernetes platform guarantee, not agent logic.

```bash
deploy/verify-lifecycle-ordering.sh              # spins up + tears down its own kind cluster
deploy/verify-lifecycle-ordering.sh --keep        # leaves the cluster up for inspection
deploy/verify-lifecycle-ordering.sh --cluster N   # reuse an existing kind cluster named N
```

Prints `PASS`/`FAIL` for each direction and exits non-zero on any failure.

## `oomkill-safety-check.yaml.tmpl` — the ordering holds under adversarial load (W-206)

Verifies, against a real cluster, W-206's acceptance criterion: under adversarial load a shrink
is a safe no-op, and under idle load a shrink actually completes — neither ever OOMKills the pod.
Uses the real agent image for **both** containers: the `warden` sidecar unchanged, and the `app`
container running the image's own `harness.LoadTarget` class instead of a real workload — a
fixture that retains a controllable amount of live heap, so the two scenarios are deterministic
instead of depending on flaky, traffic-driven memory pressure.

A real shrink attempt is driven by `harness.ShrinkTrialDriver`, `kubectl exec`'d into the running
`warden` container — a test-only entry point, not the production one (nothing wires
`ShrinkSequence` into `WardenAgent`'s own runtime loop yet; that is M3's intent handoff, W-306).
See `docs/fluencyloop/features/w-206-*/design.md` for the full reasoning.

```bash
deploy/verify-oomkill-safety.sh              # spins up + tears down its own kind cluster
deploy/verify-oomkill-safety.sh --keep        # leaves the cluster + pods up for inspection
deploy/verify-oomkill-safety.sh --cluster N   # reuse an existing kind cluster named N
```

Requires `kind`, `docker`, and `envsubst` locally; builds the agent image and loads it into the
cluster itself (no registry needed). Manual-run only for now, matching the lifecycle check above
— CI wiring (building the image and running kind inside GitHub Actions) is a deliberate
fast-follow, not part of this slice. Prints `PASS`/`FAIL` for each scenario and exits non-zero on
any failure.

## `wardenpolicy-sample-*.yaml` — the CRD schema is enforced by the API server (W-301)

Verifies, against a real cluster, that the `WardenPolicy` CRD generated from
`warden-crd-model` (via `crd-generator-maven-plugin`, at `warden-crd-model/target/classes/
META-INF/fabric8/`) actually gets its `timezone` requirement enforced by the Kubernetes API
server itself — not just checked by some Java-side validator a different client could bypass.
`wardenpolicy-sample-valid.yaml` has a `timezone`; `wardenpolicy-sample-invalid.yaml` doesn't.

```bash
deploy/verify-wardenpolicy-schema.sh              # spins up + tears down its own kind cluster
deploy/verify-wardenpolicy-schema.sh --keep        # leaves the cluster up for inspection
deploy/verify-wardenpolicy-schema.sh --cluster N   # reuse an existing kind cluster named N
```

Rebuilds `warden-crd-model` itself before applying the CRD, so the check always exercises a
freshly generated schema, not a stale one. Manual-run only for now, matching the other checks
above. Prints `PASS`/`FAIL` for each policy and exits non-zero on any failure.

## `verify-wardenpolicy-reconciler.sh` — the reconciler watches, evaluates the schedule (with lead time), respects blackout, and patches status (W-302/W-303/W-304/W-305)

Verifies, against a real cluster, that `WardenPolicyReconciler` (`warden-controller`) watches
`WardenPolicy` objects and patches `status.currentProfile` back with a real, cron-schedule-driven
value (`ScheduleEvaluator.currentProfileWithLeadTime`, W-303/W-304), and that a blackout window
really suppresses that entirely (`BlackoutEvaluator`, W-305) — not just that the selection logic
is correct in isolation (that's `ScheduleEvaluatorTest`/`BlackoutEvaluatorTest`, which also prove
DST-safety against a real historical transition and the exact lead-time boundary behavior with
fixed instants — more reliable tools for that than a live, wall-clock-dependent cluster run).
Runs the real `WardenController` process out-of-cluster, pointed at kind's own kubeconfig context
(production runs in-pod instead, where Fabric8 finds the in-cluster service-account config
automatically — `WardenController` itself is unchanged either way).

```bash
deploy/verify-wardenpolicy-reconciler.sh              # spins up + tears down its own kind cluster
deploy/verify-wardenpolicy-reconciler.sh --keep        # leaves the cluster up for inspection
deploy/verify-wardenpolicy-reconciler.sh --cluster N   # reuse an existing kind cluster named N
```

Applies two policies:

- `wardenpolicy-sample-schedule.yaml` (`* * * * *` → `always-on`, `0 0 1 1 *` → `yearly`, plus a
  `leadTime` block) — confirms `status.currentProfile` gets patched to `always-on`, deterministic
  regardless of what wall-clock time the check happens to run at, since `* * * * *` always fired
  within the last minute and is always its own soonest upcoming transition too (so `leadTime`
  never changes the outcome here — it's present to prove the field deserializes and flows through
  the real code path, not to exercise the exact early-fire boundary, which the fixed-instant unit
  tests already cover more reliably).
- `wardenpolicy-sample-blackout.yaml` — the same schedule, but with a blackout window spanning
  2000–2100 that permanently covers "now" — confirms `status.currentProfile` **never gets set at
  all**, even though the schedule alone would resolve to `always-on` on every reconcile.

Manual-run only for now, matching the other checks above.

## `verify-wardenpolicy-intent.sh` — a scheduled window actually resizes a real workload, end to end (W-306/#69)

The capstone check tying M2 to M3: a `WardenPolicy`'s schedule decision reaches the **real**
agent image (the actual init-container sidecar, not a `kubectl exec`'d test driver like W-206's)
via a pod-annotation intent handoff, and drives a real `ShrinkSequence` call — a genuine in-place
resize confirmed on the live pod(s). Covers both a directly-named `Pod` targetRef (W-306) and a
`Deployment` resolved to its live replicas via its own label selector (#69).

```bash
deploy/verify-wardenpolicy-intent.sh              # spins up + tears down its own kind cluster
deploy/verify-wardenpolicy-intent.sh --keep        # leaves the cluster + pods up for inspection
deploy/verify-wardenpolicy-intent.sh --cluster N   # reuse an existing kind cluster named N
```

Deploys two demos, both sized and configured identically to W-206's proven-working idle-load
scenario (`-Xmx350m`, `RETAIN_MB=10`, initial 400Mi/450Mi request/limit) so neither re-debugs G1
heap sizing already solved there:

- `warden-demo-e2e` (`wardenpolicy-demo-pod.yaml.tmpl`) + `wardenpolicy-demo-policy.yaml`
  (`targetRef.kind: Pod`) — confirms the single pod's actual memory limit changes from `450Mi` to
  `150Mi`.
- `warden-demo-e2e-deploy`, a **2-replica Deployment** (`wardenpolicy-demo-deployment.yaml.tmpl`)
  + `wardenpolicy-demo-deployment-policy.yaml` (`targetRef.kind: Deployment`) — confirms **every**
  replica resizes to `150Mi`, not just one.

Both policies' always-firing schedule (`* * * * *`) resolves to the same `demo-target` profile
(`100Mi`/`150Mi`). Confirms `status.currentProfile` reaches `demo-target` for both policies, and
no container ever restarts (no OOMKill).

Runs one real `WardenController` process out-of-cluster (same as the reconciler check above)
alongside the real agent image running *in* every demo pod — both sides of the intent handoff,
for both targetRef shapes, for real. Manual-run only for now, matching every other check in this
directory.

### How the fields wire up

`IntentEmitter` (controller) resolves the target profile's `request`/`limit` to bytes. For
`targetRef.kind: Pod` it PATCHes that pod's own annotations directly; for `Deployment`/
`StatefulSet` it reads the workload's own `spec.selector`, lists every live pod matching it, and
PATCHes each one identically — `warden.mnemo.io/target-request-bytes` /
`warden.mnemo.io/target-limit-bytes`, the same two keys `PodIntentReader`/`IntentWatcher` (agent)
already read. Each pod's own agent is completely unaware of this — it already only ever polls its
own pod, so **no agent-side change was needed** to support Deployment/StatefulSet targets.

Two new **required** agent env vars make the whole handoff possible: `WARDEN_POD_NAME` (Downward
API `fieldRef: metadata.name`) and `WARDEN_TARGET_CONTAINER_NAME` — both now present on
`example-sidecar.yaml` and `oomkill-safety-check.yaml.tmpl` too, since the agent fails fast
without them.

A pod created by a later rollout won't have the annotation until something re-triggers
`reconcile()` — `WardenPolicyReconciler`'s 30-second `maxReconciliationInterval` periodic resync
catches it, well within the schedule's own minute-level grain, without a per-policy dynamic
secondary-resource watch (which would need each policy's target selector known statically at
controller startup; it isn't).

**A real, explicit RBAC tradeoff, narrowed where it actually can be:** every `Pod`-targeting
example scopes its `Role` to one static, pre-known pod name via `resourceNames` — but a
`Deployment`/`StatefulSet`'s replica names are generated dynamically, so that pattern can't be
provisioned ahead of time. `wardenpolicy-demo-deployment.yaml.tmpl`'s `ClusterRole` (bound via a
namespace-scoped `RoleBinding` — identical effective scope to a `Role`, just reusable across
namespaces) grants `get`/`patch` on **every pod in the namespace** instead. See
`warden-resize-admission-policy.yaml` below for why the read (`get`) side genuinely can't be
narrowed any further natively, and how the write (`patch`) side — the actually dangerous
action — is narrowed anyway.

## `warden-resize-admission-policy.yaml` + `verify-cross-pod-resize-denied.sh` — one replica's token can never resize another (#71)

Kubernetes admission control (`ValidatingAdmissionPolicy`, and webhooks) never intercepts
`GET`/`LIST`/`WATCH` — only writes — so there is **no native mechanism** that lets a
Deployment/StatefulSet's shared-ServiceAccount replicas read only their own pod object; the
`ClusterRole` above is as narrow as the read side gets. But `pods/resize` PATCH *is* a write, and
every pod's default projected service-account token is already bound to that specific pod
(carries its own name as an `authentication.kubernetes.io/pod-name` claim, readable in a policy's
CEL expression as `request.userInfo.extra`) — even when many replicas share one ServiceAccount.
`warden-resize-admission-policy.yaml` requires that claim to match the pod actually being
resized, GA since Kubernetes 1.30 (no new version floor — this project already requires 1.35+
for in-place resize itself).

```bash
deploy/verify-cross-pod-resize-denied.sh              # spins up + tears down its own kind cluster
deploy/verify-cross-pod-resize-denied.sh --keep        # leaves the cluster + pods up for inspection
deploy/verify-cross-pod-resize-denied.sh --cluster N   # reuse an existing kind cluster named N
```

Two checks against the same 2-replica Deployment demo, using the new `harness.CrossPodResizeAttempt`
test driver (reuses `PodResizeClient` exactly as production code does):

- **Negative control** — replica A attempts to resize replica B, using replica A's own token.
  Denied with HTTP 422, citing the policy by name.
- **Positive control** — replica A resizes itself. Still succeeds — the policy doesn't block
  legitimate use, only a different pod's identity acting on this one.

Independent of the schedule/intent machinery `verify-wardenpolicy-intent.sh` covers — this is a
pure RBAC/admission-control check, with no `WardenPolicy` or controller involved at all. Manual-run
only for now, matching every other check in this directory.

## `prometheus-demo.yaml` + `verify-prometheus-metric-source.sh` — a real PromQL query reaches status (W-401)

The first M4 check: `PrometheusMetricSource` evaluates `spec.guardrail.metric` against a real
Prometheus, on the reconciler's own cadence, into `status.currentMetricValue` — purely
observational this slice; nothing acts on the value yet (W-402, W-403).

```bash
deploy/verify-prometheus-metric-source.sh              # spins up + tears down its own kind cluster
deploy/verify-prometheus-metric-source.sh --keep        # leaves the cluster up for inspection
deploy/verify-prometheus-metric-source.sh --cluster N   # reuse an existing kind cluster named N
```

Deploys a real `prom/prometheus` instance (`prometheus-demo.yaml`, default config, no scrape
targets needed) and a guardrail-only `WardenPolicy` (`wardenpolicy-demo-metric-policy.yaml`,
`guardrail.metric: "vector(1)"` — a PromQL literal expression needing no scraped data at all, so
any running Prometheus answers it identically, deterministic by construction). Since the
controller runs out-of-cluster (same as every other check here) and can't resolve in-cluster DNS
or reach a `ClusterIP` directly, this port-forwards Prometheus's `Service` to `localhost` first —
the same way any out-of-cluster tool would reach it — then points `WARDEN_PROMETHEUS_URL` at the
forwarded port. Confirms `status.currentMetricValue` reaches `1.0`. Manual-run only for now,
matching every other check in this directory.
