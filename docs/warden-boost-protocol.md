# Correlated resize transport (B3)

B3 introduced the transport and evidence path. B4 resolves admitted floors, and B5
reconciles request admission and grants. B6 still needs to harden the request and evidence
paths for untrusted callers. Ordinary scheduled intent uses the existing two annotations.

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
Acknowledgements are evidence, not a grant. The B5 reconciler checks deadlines and lifecycle
before recording permission in WardenBoost status.

## Controller boundary

PreparedTransitionStore publishes to one explicit pod after checking its UID and running
application-container instance. CorrelatedIntentEmitter provides an evidence reader that requires the current
intent and legacy target annotations to agree, the acknowledgement to match that intent,
and current applied container resources to equal the acknowledged allocation.

Desired pod spec, WardenPolicy.currentProfile, absent status, a stopped/replaced container,
and a stale acknowledgement cannot satisfy the evidence reader. B5 validates target ownership
before admission; the existing policy reconciler remains the sole intent writer.

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

B3 added no permissions to the default Helm/controller/agent deployment; B4 adds controller access to WardenBoost resources as described below. The optional
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
it atomic with a later patch to a different Pod. `prepareAndPublish` captures the target pod, persists the exact transition, checks the
current status, and patches that captured pod using its UID and resourceVersion. A later
pod write fences an older captured version. A failed pod write leaves the durable intent
for reconciliation and retry. The policy reconciler uses the helper for correlated targets with a matching admitted
request. It reads the floor by request UID and validates the policy, pod, and container
identities before preparing a replacement.

The two-resource handoff still requires serialized target ownership: a later status update
can race between the final status read and the pod patch. Consumers must compare evidence
against current status and must not infer a grant from a pod patch. The Helm controller role
can list/get WardenBoosts and patch their status; those permissions are cluster-wide, with
normal resolution restricted to the policy namespace. B5 implements exclusive claims,
status-authoritative grants, and completion.
A release target is eligible only when the controller records WorkFinished=True; otherwise
the floor remains active. During a release, shrink veto compares both target dimensions to
actual container resources. The existing agent performs the verified shrink sequence.

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

## Profile heap target

A resource profile may specify an optional `heapTarget` quantity. During a correlated
transition, the effective heap target is at least the admitted Boost floor and any selected
profile heap target. Configure it below the profile's memory limit and at or below the JVM's
launch-time maximum heap. The agent rejects unsupported targets before resizing. If the
profile omits `heapTarget`, Warden retains the previous explicit Boost heap target; a larger
container allocation alone will not raise the JVM soft maximum. During release, Warden
preserves the last target's non-heap allowance and refuses a target too small for it.

The schema bounds policies to 64 profiles and 32 Boost definitions for CEL validation cost.
A policy above either bound requires revision before CRD upgrade. Helm does not update
CRDs in its `crds/` directory during upgrade; apply the updated policy CRD explicitly.

## B5 lifecycle and target claims

The Boost reconciler requires each target Pod to opt in to its policy by carrying the
`warden.mnemo.io/policy-uid` label with the current WardenPolicy UID. It also verifies that
the Pod is selected by the referenced policy, that its UID matches the request, and that the
named application container is running. This label is an operational ownership marker;
cluster permissions must prevent untrusted workloads from setting it or forging status and
acknowledgements. Production admission packaging remains B6/M8 work.

A compare-and-set claim on the Pod serializes admission. This implementation permits one
active Boost per Pod, including when it has multiple application containers. A finalizer is
installed before reconciliation. A crash after the claim and before the admission snapshot is
persisted is retried by the same request; the policy writer holds ordinary writes during that
gap. The snapshot records the policy and container identities, resource floors, acquisition
deadline, and execution duration. Policy changes during preparation fail that acquisition;
active grants retain the admitted floor.

Preparing becomes Granted only with an acknowledgement for the current persisted transition,
matching the bound Pod and container and applied resources, before the acquisition deadline
and outside blackout. The grant names a specific instance; consumers must not reuse it after
that instance changes. Resource status and Pod status are distinct API objects, so they cannot
be read and updated as one atomic Kubernetes transaction. A later reconciliation detects an
instance change; admission and acknowledgement authentication are required before enabling
untrusted requesters.

Completion records `WorkFinished` and a release baseline. The policy writer computes the
current policy target and creates a fresh transition even if its quantities are unchanged;
only matching fresh evidence can complete release. A failed acquisition that never granted
permission is cleaned up through the same guarded release path. Expiry of granted work holds
the floor until explicit completion. Deleting an active request does not imply completion;
the finalizer retains it until safe cleanup or the bound Pod disappears. Force-removing a
finalizer bypasses that protection. If the policy disappears, release remains pending for
operator recovery. Pod/container replacement is recorded as orphaning; a replacement cannot
inherit the old request's permission.

Waiting requests expose reasons including blackout, unsupported JVM preparation, stale
acknowledgements, and blocked release. Agent failure reasons are bounded codes and do not
include exception details. Failed preparation is retried until the acquisition deadline.

The Helm controller now watches and patches WardenBoost resources in addition to status.
Upgrade the WardenBoost CRD explicitly before upgrading the controller: Helm does not update
existing CRDs automatically. The acceptance probes in `deploy/verify-boost-claim.py`,
`deploy/verify-boost-lifecycle.py`, and `deploy/verify-boost-operator.py` require a disposable
Kubernetes cluster. The lifecycle probe supplies synthetic acknowledgement evidence; it does
not prove real JVM preparation, agent identity enforcement, or production security. B6 and B7
cover the hardened request flow and end-to-end deployed JVM acceptance respectively.

Because the controller does not currently use leader election, the Helm Deployment uses one
replica and a `Recreate` rollout. New admissions and grants require the configured controller
Deployment to report one replica and the `Recreate` strategy. The chart supplies its name and
namespace through `WARDEN_CONTROLLER_DEPLOYMENT` and `WARDEN_CONTROLLER_NAMESPACE`; a custom
installation must provide those variables and equivalent Deployment configuration. If the
check fails or its API lookup is unavailable, new permission is held; existing Boost floors
and guarded release remain in effect. Do not run additional controller instances outside that
Deployment. Moving to highly available controller replicas requires leader election and its
own deployment and concurrency tests.
