# Boost B7: disposable-cluster acceptance

`deploy/verify-boost-acceptance.py` runs a real ZGC index workload with the packaged controller
and native sidecar. It installs chart resources, including cluster-scoped CRDs and admission
policies, and creates and deletes its own namespaces. **Use a disposable cluster**, never a
shared or production cluster. It requires Python 3, `kubectl`, Helm, and locally available
controller and agent images containing the current source. Kubernetes must support in-place
Pod memory resizing and native sidecars, with cgroup v2 on the node. Java must support ZGC and
runtime changes to `SoftMaxHeapSize`.

For example, build the images and load them into a freshly created kind cluster (the controller
Dockerfile is `Dockerfile.controller`):

```bash
kind create cluster --name warden-b7
# Build from the repository root.
docker build -f Dockerfile.controller -t warden-b7-controller:local .
docker build -f Dockerfile -t warden-b7-agent:local .
kind load docker-image --name warden-b7 warden-b7-controller:local warden-b7-agent:local
python3 deploy/verify-boost-acceptance.py --context kind-warden-b7 \
  --controller-image warden-b7-controller:local --agent-image warden-b7-agent:local \
  --output /tmp/warden-b7-evidence.json
kind delete cluster --name warden-b7
```

The output path must not already exist. The harness exits nonzero on failure and writes partial
evidence and diagnostics to that file. It refuses to reuse its test namespaces; its cleanup
removes finalizers from its own disposable test requests after requesting deletion. The harness
uses local image tags supplied by the caller and does not build or push images. Ensure the images
correspond to the source being tested. A kind cluster's node version determines which Kubernetes
features are available; feature gates or a different node image may be needed on other versions.

The test first runs the B6 admission and RBAC matrix. Its main workload then uses the real
sidecar and Kubernetes resize API, and verifies the grant before starting a rebuild. It records
allocation and JVM measurements, query samples, restart identity, and request statuses while
exercising expiry, concurrent requests, scheduled and emergency targets, controller and sidecar
restarts, application restart, a failed shrink followed by recovery, quota-denied growth, and
policy and target deletion. A separate isolated test agent injects a JVM heap-preparation failure
after real Kubernetes growth, then exercises the correlated release path. That fault injection is
a test seam; it does not exercise the production sidecar's JMX connection for that failure. The
security matrix uses synthetic acknowledgements; the main workload uses the real agent.

The demo sets `-XX:ZUncommitDelay=1` to make heap uncommit observable within the test timeout;
real deployments may use a different delay. The emergency metric endpoint supplies controlled Prometheus-shaped responses; it is not a
Prometheus deployment. The index workload is synthetic and remains active until explicitly
finished. The unboosted run and Boost run include measurements, but are not a controlled
performance benchmark. Sidecar cgroup snapshots show observed resource use, not incremental
Warden overhead against an otherwise identical no-sidecar baseline. Do not infer a speedup,
production capacity, or deployment-specific safety from these observations.

## Recorded run

The [recorded evidence](warden-boost-b7-kind-evidence.json) was captured on 2026-11-07 with
kind `v0.33.0-alpha+4158cd8dc9ebbf`, Kubernetes `v1.36.1` on Linux arm64, cgroup v2,
containerd `2.3.1`, kernel `6.12.76-linuxkit`, and Eclipse Temurin Java `21.0.13` with ZGC.
All seven scenario groups passed. The success scenario recorded a 30.528-second acquisition;
its full allocation, JVM, query samples, events, and statuses are in the evidence file. This is
one development-cluster observation, not a general latency or performance guarantee.
