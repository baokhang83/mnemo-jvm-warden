# Deploy examples

## `example-sidecar.yaml` — Warden as a native sidecar

A pod with two containers: a target JVM (`app`) and the Warden agent (`warden`) running as a
**native sidecar** — an `initContainer` with `restartPolicy: Always`, which starts before the app,
runs for the pod's whole life, and stops last (Kubernetes 1.29+).

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
