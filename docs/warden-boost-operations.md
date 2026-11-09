# Warden Boost: compatibility, operations, and release

Boost is experimental. This page supplements the [operator guide](warden-boost.md); it does not
replace the [scheduled-only configuration](configuration.md). Release `0.2.0` includes the local
API and Java client. Check each publication workflow and registry before using its artifacts;
do not assume older chart, image, or Maven versions contain these features.

## Compatibility and evidence

| Component | Requirement or evidence |
| --- | --- |
| Kubernetes | In-place container memory resize and native sidecars are required. Kubernetes 1.35+ provides stable in-place resize; the opt-in security package additionally requires `ValidatingAdmissionPolicy`. Verify admission and resize behavior on your distribution. |
| Node | Linux, cgroup v2, sufficient node capacity, and a runtime that applies in-place memory resizing. The published `v0.2.0` agent and controller images are `linux/amd64` only; the recorded arm64 tests used locally built images. Node policies and Pod QoS can restrict resizing. |
| Target JVM | Boost's explicit heap target needs runtime `SoftMaxHeapSize` support; use a supported ZGC or Shenandoah configuration. `-Xmx` is a fixed ceiling and must accommodate the Boost heap target. Heap is only part of container memory. |
| Application client | Java 21+, an agent built with B8, the B9 client, and a shared readable bearer-token file. The API binds loopback and is disabled unless configured. |
| Controller and security | Use matching Boost-capable controller and agent images, current CRDs and RBAC; the opt-in chart admission package must be available and enabled if using its documented trust boundary. A chart version alone does not upgrade images. |

The [B10 current-source rerun](validation/warden-boost-b10-kind-summary.json) also passed all
seven B7 scenario groups on 2026-11-08, using images built from commit `94f07e2`. The recorded
[B7 run](validation/warden-boost-b7-kind.md) used Kubernetes 1.36.1, Linux arm64,
cgroup v2, containerd 2.3.1 and Temurin 21.0.13/ZGC. It tests a manual request against a real
controller, sidecar, JVM and resize API. Its fault fixture and security matrix have the explicit
limits described there. B8's local API and B9's Java client have separate disposable-cluster
checks; these probes scale their chart controller to zero, and B9 verifies uncertain timeout
retention without a policy or controller reconciliation. Both probes passed on the B10 kind
cluster on 2026-11-08 with the current agent and example images. A
single real-client-to-grant-to-reclaim integration test and a Shenandoah deployment have not
been established by that evidence. No performance or cost savings are implied.

To repeat the separate B8 and B9 checks on a disposable kind cluster with the current source,
load the agent and example images, then run the probes **sequentially**: each installs its own
cluster-scoped chart resources and admission policies. Use a fresh cluster and do not run these
alongside the B7 acceptance harness:

```bash
kind create cluster --name warden-boost-check
# From the repository root:
docker build -f Dockerfile -t warden-agent-check:local .
docker build -f deploy/Dockerfile.boost-client-example -t warden-example-check:local .
kind load docker-image --name warden-boost-check warden-agent-check:local warden-example-check:local
python3 deploy/verify-boost-local-api.py --context kind-warden-boost-check \
  --agent-image warden-agent-check:local
python3 deploy/verify-boost-client.py --context kind-warden-boost-check \
  --agent-image warden-agent-check:local --example-image warden-example-check:local
kind delete cluster --name warden-boost-check
```

The probes install resources and remove their test namespaces. They are not production
installation instructions; see the operator guide for trust and setup requirements.

ZGC returns unused heap subject to `-Xms` and `ZUncommitDelay`; the B7 demo sets a short delay
for its test. Real workloads may retain references or native memory, and reclaim can take longer
or remain blocked. `SoftMaxHeapSize` is a target, not a hard heap cap. Validate the complete
container memory footprint and headroom against the chosen normal limit on your workload.

## First response

Record the namespace, request name, policy name, Pod UID, app container ID, phase, condition
reasons, and relevant timestamps. Compare the live Pod with the request's bound identity and
check applied resources in Pod **status**, not only desired resources in its spec:

```bash
kubectl -n "$WARDEN_NS" get wardenboost "$BOOST_REQUEST" -o yaml
kubectl -n "$WARDEN_NS" get wardenpolicy "$WARDEN_POLICY" -o yaml
kubectl -n "$WARDEN_NS" get pod "$WARDEN_POD" -o yaml
kubectl -n "$WARDEN_NS" describe pod "$WARDEN_POD"
kubectl -n "$WARDEN_NS" logs "$WARDEN_POD" -c warden --tail=200
kubectl -n "$WARDEN_CONTROL_NS" logs deployment/"$WARDEN_CONTROLLER" --tail=200
```

Select the correct namespace, Pod and container for your installation. Treat annotations and
logs as operational data; do not paste bearer tokens or service-account credentials into an
incident report. Events and process-local metrics are diagnostic; request status and the live
Pod's identity and applied resources are needed to understand the current state.

| Situation | Action |
| --- | --- |
| Growth deferred or denied | Inspect Pod resize conditions, node capacity, quota, admission and agent logs. Do not start work without a matching grant. Allow the request to time out or follow its status through guarded cleanup. |
| Unsupported JVM or heap target | Check collector capability, the running JVM's `-Xmx`, configured soft target, attach/JMX access and logs. A failed preparation can leave a larger allocation while cleanup runs. |
| Client timeout, interrupted call, or lost response | Preserve the stable request ID. Inspect it before retrying; retry acquisition with the **same** ID when appropriate. A timeout does not prove a request was not accepted or granted. A caller-supplied ID is useful if the process may lose the generated one. |
| Expired while work runs | Expiry is not cancellation. Stop or finish the operation and child work, then request completion; the admitted floor can remain held until then. |
| `ReleaseBlocked` with a message starting `scheduled profile` | The scheduled profile's `limit` is not larger than the non-heap allowance (boosted limit minus heap) kept from the Boost; the message gives both numbers. Raise that profile's `limit`, or narrow the boosted profile's limit/`heapTarget` gap. The release resumes on the next reconcile (about 30 s). |
| `ReleaseBlocked` or reclamation slow | Confirm completion and inspect live heap, native memory, `-Xms`, collector uncommit behavior and agent logs. Free application-owned references where safe and allow retries; do not force a smaller limit. |
| Policy missing, deleted or recreated | Inspect the request's bound policy identity and current policy. A missing or replacement policy can leave release pending; do not assume a same-named replacement authorizes cleanup. Follow the site's recovery process. |
| Agent/controller restart | Inspect current phase, transition and identity; the agent's local API token rotates on restart. The Java client re-reads it per call. Restart alone is not permission to repeat application work. |
| App container restart or target deletion | Compare Pod UID and container ID with the grant. An old grant must not authorize work in a replacement instance; inspect `Orphaned` and claim cleanup. |
| Local API returns 401 or cannot read token | Check that the API is enabled, token volume is shared, and the app's effective UID can read the agent-owned `0600` file. Check paths and token rotation without printing the token. |

Completion is a request for guarded reclamation, not proof that reclamation has finished.
Confirm `Completed`, the actual applied allocation, and claim cleanup. An extra allocation may
remain after failure, expiry, or policy loss. If ordinary recovery is impossible, involve the
cluster operator, establish whether work is still running, and use a site-specific emergency
procedure. Force-removing a finalizer or lowering a limit bypasses protections and can interrupt
work or cause an OOM; this guide does not prescribe it as routine recovery.

## Preparing a release

Release from the reviewed and merged commit. Before choosing a version, confirm that it is unused
and greater than existing releases **and** both published chart versions; chart tags cannot be overwritten. Inspect the repository's releases and tags at release time. Do not reuse a version if a previous
partial publication already created any of its artifacts.

1. Verify the merged commit, full CI, both chart lints, and the relevant disposable-cluster
   acceptance probes. Review the evidence and note its limits. Confirm the Maven Central and
   signing credentials are configured without exposing their values.
2. Prepare release notes from [the Boost release notes](releases/warden-boost.md), including
   the exact commit, version, compatibility and verification results. Create the application
   `vX.Y.Z` tag on that merged commit and push the tag, then publish the matching GitHub
   release using the project's normal release process. A tag push triggers `publish-maven` and `publish-charts-release`; publishing the
   GitHub release triggers the two container image jobs. The workflows have different triggers:
   a tag alone does not publish the images. Do not assume a GitHub Actions-created tag/release
   will trigger another workflow through `GITHUB_TOKEN`.
3. Monitor all three workflows. Verify Maven Central contains `warden-boost-client` **and** its
   `warden-protocol` dependency at `X.Y.Z`, both image tags exist in GHCR, and both chart tags
   and the Helm repository index contain `X.Y.Z`. Central publication can take time. Check actual
   run results and artifacts; a successful job is not evidence that every expected consumer can
   resolve them.
4. On a clean Maven cache and disposable compatible cluster, consume the published artifacts,
   pin image/chart versions explicitly, run the application's Boost flow and scheduled-only
   smoke checks, and record the results. Do not claim the full published flow is verified until
   this is done.

If publishing partially fails, inspect each registry and workflow before retrying. Existing
chart or Maven versions may be immutable; choose a new version when necessary instead of
silently overwriting or skipping an artifact. The chart-only workflow runs on chart changes to
`main`; the application-tag workflow separately stamps both charts to the release version.
