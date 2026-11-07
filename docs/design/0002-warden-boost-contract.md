# Warden Boost contract

Status: proposed implementation contract for B1, issue #105.
Date: 2026-11-07.
Implementation: B2–B10, issues #106–#114. No Boost API is shipped by this document.

## Purpose and boundaries

An application requests an operator-approved memory profile for temporary work in its
existing pod. Work starts only after a confirmed grant. Completion releases the reservation;
Warden then reconciles the current normal policy through its existing verified resize path.

Version one supports one unresolved request per pod/container. It supports memory only.
The manual Kubernetes flow ships before the local API and Java client. CPU, concurrent
boost composition, automatic estimation, and forced application cancellation are deferred.
A grant is resource coordination, not a guarantee against OOM or a guarantee of performance.

## Policy and request

Use namespaced `warden.mnemo.io/v1alpha1` resources, matching WardenPolicy conventions.
The proposed additions are:

```yaml
# Under WardenPolicy.spec
boosts:
  index-rebuild:
    profile: maintenance
    heapTarget: 2Gi
    acquisitionTimeout: 2m
    maxDuration: 20m
```

`profile` references an existing request/limit pair. `heapTarget` is a required JVM soft
maximum for the boost, separate from the container limit. Require positive quantities,
request <= limit, heapTarget < limit, and heapTarget <= the observed launch-time maximum
heap. The operator must size the remaining memory for non-heap use and operational margin;
Warden does not infer that allowance or guarantee that it is sufficient. Unsupported
runtime control prevents admission. Existing scheduled profiles retain their fields. Profile quantity strings are limited to
64 characters so Kubernetes can bound CEL evaluation cost. Up to 32 boost definitions
are allowed per policy. Existing configurations with longer quantity strings need revision.

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenBoost
metadata:
  name: index-rebuild-123
spec:
  policyRef: reporting
  podRef:
    name: reporting-abc
    uid: actual-pod-uid
  container: app
  boost: index-rebuild
  completionRequested: false
```

The namespace comes from request metadata. No cross-namespace target field is accepted.
All spec fields except completionRequested are immutable. Completion is monotonic: false
to true only. Application code cannot provide quantities or an arbitrary heap target.

At admission, bind and persist request UID, policy UID/generation, pod UID, container name,
container instance ID, approved memory floor, heap target, and deadlines. Resolve the
policy's current authorization and target opt-in before taking the claim. Policy edits or
deletion do not erase admitted floors. New requests use current policy definitions.

Use Kubernetes resource identity as the idempotency key. A retry reads the same request;
creating a different request for the same claimed target is a conflict, not a second grant.

## Durable status and ownership

The controller is the only WardenBoost status writer. Proposed status fields are:

- phase and observedGeneration;
- admitted target identity, policy identity, and approved allocation snapshot;
- acquisitionDeadline, grantedAt, executionDeadline, and completedAt;
- current transition ID and the allocation/heap values confirmed for that transition;
- conditions: Accepted, CapacityReady, JVMReady, WorkFinished, Released, RecoveryRequired;
- conditions carry status, reason, message, observedGeneration, and lastTransitionTime.

Admission requires an atomic, durable per-target claim before issuing a resource intent.
Use optimistic concurrency or another Kubernetes-backed atomic claim. A process-local lock
is insufficient. Keep exclusivity through blocked release or expired/uncertain work.
Select the exact claim representation in B5; prove concurrent request behavior there.

Agent evidence uses a separate constrained channel. The agent cannot create a Granted
status directly. User credentials that can complete a request must be authorized for its
bound target; Kubernetes RBAC alone cannot enforce the content of a CRD create request.

## State transitions

| Current state | Event and evidence | Next state | Required action |
| --- | --- | --- | --- |
| Pending | Authorization, identity, definition, or capability invalid | Rejected | Record reason; issue no growth intent. |
| Pending | Another unresolved request owns target | Rejected | Record TargetBusy; do not queue indefinitely. |
| Pending | Deadline reached before admission | Failed | Record AcquisitionTimedOut. |
| Pending | Valid request and durable claim obtained | Preparing | Persist snapshot and floor before emitting intent. |
| Pending | Completion requested before admission | Completed | Record CancelledBeforeGrant; no operation was authorized. |
| Preparing | Matching current evidence confirms allocation and JVM target before deadline | Granted | Persist grant time and execution deadline. |
| Preparing | Capacity deferred, evidence missing, or blackout | Preparing | Reconcile with bounded waits until acquisition deadline. |
| Preparing | Rejection, JVM preparation failure, or acquisition timeout | Failed | No permission; reconcile partial allocation through verified cleanup. |
| Preparing | Completion requested | Releasing | No new grant; clean partial allocation safely. |
| Granted | Authorized completion | Releasing | Record WorkFinished and resolve current policy target. |
| Granted | Execution deadline reached without completion | Expired | Keep floor and claim; set RecoveryRequired. |
| Expired | Authorized completion or documented operator confirmation work stopped | Releasing | Record evidence and begin normal release. |
| Releasing | Current policy target applied with matching evidence, or fresh evidence proves it already holds | Completed | Mark Released, then release claim/finalizer. |
| Releasing | Blackout, shrink veto, or failed RSS verification | Releasing | Keep safe observed allocation; report ReleaseBlocked and retry. |
| Failed | Partial-growth cleanup completed or target disappeared | Failed | Mark Released and release claim/finalizer. |
| Preparing / Granted / Expired / Releasing | Bound pod/container instance disappears or changes | Orphaned | Invalidate old permission; reconcile replacement under normal policy. |

Rejected and Completed are terminal. Failed and Orphaned are terminal for authorization,
but may still require cleanup. Their phase does not imply resources have been reclaimed.
For Orphaned, release the old claim only after old-instance disappearance is confirmed and
any pending old intent is fenced. Normal policy owns the replacement; it cannot use the grant.

If a newer transition supersedes a release target, reconcile the newer policy target before
marking Released. Do not acknowledge a release from stale observed values.

## Deadlines, completion, and deletion

Acquisition deadline = Kubernetes creationTimestamp + admitted acquisitionTimeout.
Execution deadline = durable grantedAt + admitted maxDuration. Reconciliation uses persisted
UTC instants and an injected clock. At equality, the deadline has expired. A restarted
controller does not reset either interval.

A client timeout or disconnection is not proof that work never started. When grant outcome
is uncertain, retain the floor/claim until an authorized completion or operator recovery.
The SDK must keep the stable request ID available for lookup. It must not automatically
complete an uncertain request merely because a network call timed out.

Completion is a promise by the authorized caller that work and child work stopped using
the reservation. Close requests completion; it does not promise immediate reclamation.
An execution deadline cannot stop Java code and never forces a memory shrink.

Deletion is not completion. Install a finalizer before growth. Deletion before any possible
grant can cancel acquisition and trigger verified cleanup. Deletion after a possible grant
holds the floor until completion, explicit operator recovery, or confirmed target loss.
Document force deletion as bypassing this protection. No automatic TTL deletes unresolved work.

## Effective target and transition evidence

Retain one desired-intent writer per pod/container. Preserve the existing normal precedence
(blackout, emergency signal, schedule) and apply admitted boost floors to the resolved normal
target. Combine memory request and limit component-wise using maxima. A boost cannot lower
an emergency target. Apply shrink vetoes against observed state after target resolution.

Blackout prevents new grants and resource transitions. Completion can be recorded during
blackout, but release waits. Existing grants retain their floor. On release, recompute the
current schedule/emergency target rather than restoring a pre-boost snapshot.

A boost intent adds request UID, transition ID, pod UID, container instance, memory request,
memory limit, and explicit heap target. Include a protocol version. Agent evidence echoes
those fields, outcome, applied allocation, observed JVM target, and completion time.

Grant requires matching identity and transition, fresh confirmation of applied Kubernetes
resources, and successful required JVM preparation. A PATCH response, desired spec,
currentProfile, or absence of metric changes is insufficient. Existing sufficient allocation
still needs current evidence. The reader must not treat a new transition as applied because
its numeric resource values match an older acknowledgement.

Grow ordering stays allocation confirmed → JVM target raised → grant evidence. Shrink
ordering stays heap/cache adjustment → reclamation verification → resource reduction.
Never add a separate cleanup resize implementation. Verify explicit heap targets before
use; current GrowSequence's container-limit-to-soft-max mapping needs extension in B3.

Cache preWarm failures remain separately reported and do not certify index readiness. A
grant confirms capacity and JVM preparation; application code owns building the new index.
Do not raise a boost heap floor into an unconfirmed container allocation.

## Reason codes

Use stable condition reasons; diagnostic messages may add details without credentials.

| Area | Reasons |
| --- | --- |
| Admission | PolicyNotFound, BoostNotDefined, TargetNotAuthorized, IdentityMismatch, TargetBusy, InvalidProfile, UnsupportedRuntime, HeapTargetUnsupported |
| Preparation | BlackoutActive, ResizeDeferred, ResizeRejected, JVMPreparationFailed, AcknowledgementStale, AcquisitionTimedOut |
| Execution | Granted, CompletionRequested, ExecutionExpired, RecoveryRequired, TargetReplaced |
| Release | Released, ReleaseBlocked, ShrinkVetoed, VerificationFailed, CleanupPending, CancelledBeforeGrant |

Admission inability before side effects is Rejected. Preparation failure after admission is
Failed even if growth did not happen. Conditions explain resource and cleanup state.

## Acceptance scenarios for subsequent implementation

| Scenario | Expected result | Implementation evidence |
| --- | --- | --- |
| Successful boost and completion | Grant after confirmation; current-policy release via verified path | B3/B5/B7 |
| Kubernetes rejects or defers growth | No permission before actual allocation; deadline persists | B3/B5/B7 |
| Growth succeeds, JVM preparation fails | Failed, no grant; verified partial-growth cleanup | B3/B5/B7 |
| Old acknowledgement arrives | Cannot satisfy another request/transition or replacement container | B3/B5 |
| Two simultaneous requests | Exactly one durable claim; other receives TargetBusy | B5 |
| Controller/agent restart | Rebuild from durable state; no new deadline or duplicate permission | B3/B5/B7 |
| Deadline, disconnect, or deletion after grant | Floor held until completion/recovery; actionable condition | B5/B7/B8/B9 |
| Application restart/pod replacement | Old grant invalid; new instance follows normal policy | B3/B5/B7 |
| Schedule/emergency/policy changes | Active floor preserved; release uses current policy | B4/B5/B7 |
| Release RSS gate fails | No unsafe downsize; Releasing with ReleaseBlocked | B5/B7 |
| Unauthorized request/completion/acknowledgement | Denied at applicable deployed boundaries | B6/B7/B8 |
| Policy without Boost settings | Existing scheduled behavior preserved | B2/B3/B4 |

B1 is complete when this contract covers identity, expiry, deletion, restart, partial growth,
and confirmation semantics. B1 adds no runtime behavior; CRD generation starts in B2.

## Compatibility and implementation handoff

Keep existing intent keys and scheduled APIs compatible until a versioned protocol is
implemented. New Boost requests must remain ungranted with an old/unsupported agent.
Do not permit fallback from missing acknowledgement to inferred success. Exact annotation
keys, atomic-claim storage, and transport authentication are implementation decisions for
B3/B5/B6/B8, constrained by this contract and M8 #97–#102.

The heapTarget requirement refines the development plan's unresolved non-heap question.
A separate heap target is mandatory for boost definitions; a universal non-heap estimate is
not introduced. Changes to existing scheduled heap behavior need their own compatibility
assessment. This document does not modify existing policy semantics or claim deployed proof.
