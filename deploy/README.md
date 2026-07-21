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

## `verify-wardenpolicy-reconciler.sh` — the reconciler watches, evaluates the schedule, and patches status (W-302/W-303)

Verifies, against a real cluster, that `WardenPolicyReconciler` (`warden-controller`) watches
`WardenPolicy` objects and patches `status.currentProfile` back with a real, cron-schedule-driven
value (`ScheduleEvaluator`, W-303) — not just that the selection logic is correct in isolation
(that's `ScheduleEvaluatorTest`, which also proves DST-safety against a real historical
transition). Runs the real `WardenController` process out-of-cluster, pointed at kind's own
kubeconfig context (production runs in-pod instead, where Fabric8 finds the in-cluster
service-account config automatically — `WardenController` itself is unchanged either way).

```bash
deploy/verify-wardenpolicy-reconciler.sh              # spins up + tears down its own kind cluster
deploy/verify-wardenpolicy-reconciler.sh --keep        # leaves the cluster up for inspection
deploy/verify-wardenpolicy-reconciler.sh --cluster N   # reuse an existing kind cluster named N
```

Applies `wardenpolicy-sample-schedule.yaml` (`* * * * *` → `always-on`, `0 0 1 1 *` → `yearly`)
and confirms `status.currentProfile` gets patched to `always-on` — deterministic regardless of
what wall-clock time the check happens to run at, since `* * * * *` always fired within the last
minute. Manual-run only for now, matching the other checks above.
