# Correlated resize transport (B3)

B3 adds the transport and evidence path used by later Boost reconciliation. It does not
watch WardenBoost requests, grant leases, resolve active floors, or enable application work.
Those behaviors remain B4–B6. Ordinary scheduled intent uses the existing two annotations.

## Shared wire contract

`warden-protocol` is a dependency-free module shared by the controller and agent. It owns:

- ResizeIntent: transition ID, originating request UID, pod UID, container name and instance
  ID, memory request, memory limit, and explicit heap target.
- ResizeAcknowledgement: the exact intent, success/failure, reason, observed request/limit/
  heap values, and completion time.

Annotations are `warden.mnemo.io/resize-intent` and `warden.mnemo.io/resize-ack`.
The existing target-request-bytes and target-limit-bytes annotations remain alongside the
correlated intent and must match it. A malformed, conflicting, or unsupported correlated
payload never falls back to legacy execution.

The encoding is `v1` followed by dot-separated, unpadded base64url UTF-8 fields. Intent
fields appear in the order listed above; acknowledgement fields are encoded intent,
applied (`true`/`false`), reason, observed request, observed limit, observed heap, and UTC
completion instant. Byte counts are decimal strings, avoiding floating-point precision loss.
Both payloads are bounded to 8192 characters. Decode rejects unknown versions, field counts,
noncanonical base64, missing identities, invalid quantities, and invalid timestamps.

The producer supplies a durable transition ID. Repeated emission must reuse it. A new
request or transition must get a new identity even when its numeric targets are unchanged.
Acknowledgements are evidence, not a grant. B5 will validate deadlines and lifecycle before
recording permission in WardenBoost status.

## Controller boundary

CorrelatedIntentEmitter emits to one explicit pod after checking its UID and running
application-container instance. It provides an evidence reader that requires the current
intent and legacy target annotations to agree, the acknowledgement to match that intent,
and current applied container resources to equal the acknowledged allocation.

Desired pod spec, WardenPolicy.currentProfile, absent status, a stopped/replaced container,
and a stale acknowledgement cannot satisfy the evidence reader. B4/B5 must resolve ownership
and authorization before calling the emitter; B3 does not wire a second competing intent
writer into the normal policy reconciler.

## Agent preparation

The agent checks the target identity and runtime heap ceiling before acting. Explicit heap
preparation currently requires a collector with observable runtime SoftMaxHeapSize support
(ZGC/Shenandoah). G1 keeps its existing scheduled behavior but cannot produce a successful
correlated soft-max acknowledgement through this path.

GrowSequence and ShrinkSequence accept an explicit heap target. Their legacy overloads
retain the existing container-limit mapping. Growth confirms Kubernetes allocation before
raising the heap target. A reduction of either request or limit uses the verified shrink
sequence. Equal allocation still requires JVM preparation and a fresh observation.

After preparation, the agent re-reads applied allocation and instance/intent identity and
reads back the heap target. Only matching positive evidence produces an applied acknowledgement.
Failures produce a non-applied acknowledgement when the original identity is still current.
The probe does not claim cache/index readiness; application code still owns that work.

Correlated resource writes include UID and resourceVersion preconditions and recheck the
current intent/instance. Acknowledgement writes use JSON Patch tests for UID/resourceVersion
from the observation. Concurrent pod changes reject the write; the next poll reconciles
again. Acknowledgement failure does not undo prepared capacity or trigger a blind shrink.

## Permissions and trust

B3 grants no new permissions to the default Helm/controller/agent deployment. The optional
`deploy/warden-boost-ack-admission-policy.yaml` demonstrates a dedicated ServiceAccount
named `warden-boost-agent` and must be installed before granting that identity pods/patch.

The policy restricts its writes to its token-bound pod name/UID and the bounded resize-ack
annotation, preserving other annotations, labels, owners, finalizers, spec, and status.
A second policy denies acknowledgement changes by unrelated identities, including the
controller. Existing scheduled annotation writes remain available to the controller.
These are cluster-wide guards; use only on a disposable cluster for the supplied harness.
Production packaging and configurable identities remain B6/M8 #97–#102.

A pod-bound token identifies a pod, not a container. Mount the agent token only in the
trusted agent; a compromised application with that token could impersonate it. Shared
process/network isolation and credential access remain explicit M8 threat-model concerns.
Admission does not restrict GET access. Do not treat the annotation payload as a signature
or a boundary against a compromised trusted agent or cluster administrator.

## Verification

```bash
mvn -B --no-transfer-progress verify
kind create cluster --name warden-boost-protocol-check --wait 90s
python3 deploy/verify-boost-protocol.py --context kind-warden-boost-protocol-check
kind delete cluster --name warden-boost-protocol-check
```

Unit tests cover protocol precision/validation, stale request and instance identity, applied
status versus desired spec, equal-allocation preparation, ordering, rejected growth,
verification failure, runtime ceiling, and transition changes during preparation.

The live harness runs a single Java/ZGC container on Kubernetes v1.36.1. It uses the
production reader, identity-bound resize client, orchestration, and acknowledgement writer,
with a local JVM heap adapter. It confirms real applied resource growth and soft-max
readback; a second transition with identical numbers must produce new evidence. Negative
checks exercise own-pod admission, unrelated annotation/intent writes, acknowledgement
forgery, and stale resourceVersion tests on pod and resize endpoints.

The harness creates a temporary namespace, configmap, ServiceAccount, and RBAC plus the
cluster-wide admission guards. It removes them afterward and refuses to reuse its namespace.
A temporary pod-bound credential is kept in a private temporary kubeconfig and removed.
Run it only on a disposable cluster. It is transport/heap evidence, not a complete production
controller/sidecar/lifecycle deployment or a replacement for B7's failure matrix.

Helm packaging and CI reactor builds include the protocol module. No runtime JSON library
is added to the agent. Later protocol changes must preserve versioned compatibility and
fail closed when an older agent cannot acknowledge a Boost request.

## Persisting a replacement transition

`PreparedTransitionStore.prepare` writes `status.transitionId` and the complete encoded
`status.preparedIntent` together in one JSON Patch on the WardenBoost status subresource.
The patch tests both the request UID and its observed resourceVersion. Concurrent changes
reject the patch; the caller must re-read and reconcile. Even an idempotent retry performs
the version check. The current transition ID cannot be reused for a different target. The caller must ensure
IDs are unique across earlier transitions as well; the status stores no complete ID history.

The store verifies the admitted pod/container identity, active phase, and resource/heap
floor before the patch. `current` decodes the persisted target and checks its identity and
transition ID. Authorization, policy ownership, and exclusive target claiming remain the
caller's responsibility. The store does not grant a Boost.

This status update is atomic **within the WardenBoost resource**. Kubernetes does not make
it atomic with a later patch to a different Pod. The policy writer is not connected to this
store yet: before doing so, it needs a single-writer protocol that prevents a delayed older
publisher from overwriting a newer transition. No pod patch may be treated as proof that a
transition remains current. B4 remains incomplete until that integration and its restart/
race tests are implemented. No additional RBAC is enabled by this change.

For a real API-server test on a disposable cluster:

```bash
mvn -B --no-transfer-progress -pl warden-controller -am package
kind create cluster --name warden-atomic-check --wait 90s
python3 deploy/verify-prepared-transition.py --context kind-warden-atomic-check
kind delete cluster --name warden-atomic-check
```

The harness installs the WardenBoost CRD, creates its own namespace, and checks complete
status persistence, floor and identity rejection, ID reuse, stale replacement, and a stale
idempotent retry. It does not publish an intent to a pod.
