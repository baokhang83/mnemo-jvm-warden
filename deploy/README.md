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

> **Known issue:** the agent currently cannot attach to a target running as a different UID (the
> default here, since the agent hardens to a non-root user and most app images run as root) — see
> [#55](https://github.com/baokhang83/mnemo-jvm-warden/issues/55). `/readyz` will stay `503` until
> that's resolved or the pod's containers are given matching UIDs.

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
