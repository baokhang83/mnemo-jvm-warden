# Warden Boost — development plan

Status: proposed development plan. This document does not describe shipped behavior.
Date: 2026-11-07.

## Goal

Let an application request temporary memory headroom for an operation that must run
inside its existing Kubernetes pod. Warden grants permission only after Kubernetes
capacity and JVM preparation are confirmed. After the application reports completion,
Warden returns to the current policy target through the existing verified shrink path.

The reference use case is rebuilding an in-memory index while the application continues
to serve queries. Other candidates include reference-data refreshes and ruleset replacement.
Independent batch work that can execute elsewhere remains a candidate for a Kubernetes Job.

## Scope and delivery boundaries

The first milestone supports operator-created requests and explicit completion through
Kubernetes. It includes memory profiles, one active boost per pod/container, durable state,
agent acknowledgements, and failure recovery. It reuses the current controller and agent.

The second milestone adds an application-facing sidecar endpoint and a small Java client.
No application integration is required to validate the first milestone.

CPU boosts, distributed operation coordination, overlapping boosts on one container,
automatic demand estimation, resource borrowing between pods, and automatic interruption
of application work are outside the initial scope. Warden does not guarantee node capacity,
prevent every OOM, or change the JVM launch-time maximum heap.

## Existing integration points

| Existing area | Planned change |
| --- | --- |
| `warden-crd-model` | Add approved boost definitions and the `WardenBoost` resource model. |
| `WardenPolicyReconciler` and `PrecedenceEngine` | Resolve normal policy and boost floors into one per-pod intent. |
| `IntentEmitter` | Emit correlated per-pod intents; preserve ordinary workload targeting. |
| Agent `Intent`, `IntentWatcher`, and `PodState` | Carry transition identity and report observed completion. |
| `GrowSequence` and `ShrinkSequence` | Reuse resource ordering, confirmation, and shrink verification. |
| `warden-cache-api` | Preserve cache hooks; do not use them as boost ownership records. |
| Helm charts, CRDs, and admission controls | Deploy schemas and constrain request and acknowledgement writers. |
| `examples` | Add an index rebuild demonstration and failure scenarios. |

Implementation must inspect the current code before editing these areas. In particular,
`status.currentProfile` currently records a controller decision, not successful agent
application. It must never be used as evidence that a boost is granted.

## Proposed policy contract

The operator defines named boosts that refer to existing resource profiles:

```yaml
# Proposed additions to an existing WardenPolicy spec.
profiles:
  normal:
    request: 1Gi
    limit: 2Gi
  maintenance:
    request: 3Gi
    limit: 4Gi
boosts:
  index-rebuild:
    profile: maintenance
    acquisitionTimeout: 2m
    maxDuration: 20m
```

Validate profile references, positive durations, valid quantities, and request <= limit.
Validate runtime capability and the launched JVM maximum before granting. A boost must
not silently promise heap capacity above the fixed `-Xmx`. Define and expose the effective
heap target separately from total container memory, allowing non-heap headroom.

## Proposed request contract

Use a namespaced CRD with the same API group/version conventions as WardenPolicy.
The example omits apiVersion until the schema is implemented.

```yaml
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

Bind requests to a pod UID, container, policy UID, and application container instance.
The controller records the admitted policy generation and resource floor. Immutable
request identity prevents a request from being retargeted after admission. Only the
completion flag can advance from false to true. A stable request ID makes retries
idempotent. Concurrent requests for the same pod/container receive an explicit conflict.

The acquisition deadline starts at request creation. The execution deadline starts when
the grant is durably recorded. Server-side status stores both timestamps. Initial
completion uses a spec patch, not deletion. The controller owns status.

## Lifecycle

| Phase | Meaning and next action |
| --- | --- |
| Pending | Validate identity, policy, capability, deadline, and exclusivity. |
| Preparing | Hold the approved resource floor and issue a correlated intent. |
| Granted | Matching agent evidence confirms resources and JVM readiness; work may start. |
| Releasing | Completion was requested; remove the operation floor and reconcile current policy. |
| Completed | The release target was applied, or the current policy already requires the allocation. |
| Rejected | Validation, authorization, or exclusivity prevents admission. |
| Failed | Acquisition failed; no permission to start work was issued. Reconcile partial growth safely. |
| Expired | Execution deadline passed; work termination is unknown. Retain the floor pending recovery. |
| Orphaned | Pod/container identity changed; the original grant cannot authorize the replacement process. |

Expose conditions and reason codes for deferred allocation, timeout, stale acknowledgement,
blackout, unsupported JVM, release blocked, and cleanup pending. A failed acquisition may
still have increased resources; phase alone must not imply cleanup finished.

Expiry is an alert and a recovery state, not a revocation mechanism. It cannot stop Java
code. Keep the resource floor until explicit completion or a documented operator recovery
procedure establishes that the operation stopped. This deliberately favors safety over
bounded resource retention.

Do not garbage-collect unresolved requests. If deletion is requested while active, retain
a finalizer and the floor until safe release or confirmed target disappearance. Document
force deletion as an operator escape hatch that bypasses these protections.

## Target resolution and ownership

Maintain one effective intent writer for each pod/container. A boost reconciler must not
patch resource intent independently of the existing policy reconciler.

For memory, combine the normal policy target, emergency target, and admitted boost floor
using component-wise maxima of request and limit. Profiles are not necessarily totally
ordered. Apply shrink vetoes after resolving the candidate against observed state.

Preserve blackout as a hard prohibition on new resource transitions. Do not grant a new
boost during blackout, even if a profile appears to fit. Existing grants retain their floor.
Completion can be recorded during blackout, but resource release waits until it ends.

Recompute the normal policy target at release time. Do not restore a saved pre-boost
profile: the schedule or emergency state may have changed during the operation.

Use Kubernetes watches or bounded scheduled reconciliation for request, policy, pod, and
acknowledgement changes. Survive controller restart by reconstructing state from durable
resources. Handle requests from multiple controller instances with optimistic concurrency
and an atomic per-target claim; reject an unsupported multi-controller deployment if the
current controller lacks leader election. Do not rely on a JVM-local lock for exclusivity.

Policy edits or deletion cannot erase an active admitted floor. Preserve its recorded
allocation and target identity until release or recovery. Define resource ownership with
VPA, GitOps, and other resize writers; surface conflicts instead of repeatedly fighting them.

## Correlated agent acknowledgement

Each intent includes a unique transition ID, target pod/container identity, target memory
values, and originating boost identity when applicable. Agent acknowledgement includes
those identifiers, applied values, application container instance, completion time, and outcome.

Grant only after observing the matching transition and current identities. Evidence must
confirm actual Kubernetes allocation and successful required JVM preparation, including
when the existing allocation already satisfies the floor. A PATCH response, desired pod
spec, unchanged metric, or controller-selected profile is insufficient evidence.

Keep desired state and observed state distinct. An agent restart or delayed old message
must not grant a new request. A target application restart invalidates the old permission;
prevent replacement work from using that grant and reconcile partial allocation safely.

The initial acknowledgement transport should fit the existing annotation handoff if its
size and permissions remain bounded. Scope the writer to its own pod and validate the
allowed annotation keys. Keep acknowledgement writes independent from unrelated telemetry.

## Application client milestone

Proposed usage, subject to implementation:

```java
try (BoostLease lease = warden.acquireBoost(
        "index-rebuild", Duration.ofMinutes(2))) {
    rebuildIndex();
}
```

Add a lightweight `warden-boost-client` module and a local sidecar API. Acquisition returns
only after a durable grant. Closing the lease requests completion and is idempotent; it
need not block until memory reclamation finishes. Provide a way to inspect release status.

The client acquisition timeout cannot extend the operator deadline. If a caller times out
or disconnects while a grant may be racing, retain an uncertain grant for reconciliation;
never assume the operation could not have started. Use stable IDs for lookup and retry.

Keep the local API pod-bound and authenticated with an explicit application-to-sidecar
credential or equivalent verified identity mechanism. Loopback binding alone is not an
authorization boundary between containers sharing a pod network. Never accept arbitrary
pod targets, resource quantities, or Kubernetes API paths from the application.

The SDK does not need Kubernetes credentials. The sidecar request mechanism must enforce
self-pod identity through admission as well as RBAC; RBAC alone cannot validate arbitrary
fields inside a newly created custom resource. Reuse the repository's existing security
patterns and verify the deployed admission policy with cross-pod negative tests.

Application code must close only after the operation and its child work finish. Asynchronous
operations and cancellation require explicit lifecycle handling; defer convenience wrappers.

## Task breakdown

Sizes indicate relative effort, not calendar estimates. Each task should be a reviewable change.

| ID | Task | Size | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| B1 | Define request lifecycle, contracts, reason codes, and invariants | S | None | State table covers expiry, deletion, restart, and partial growth. |
| B2 | Add boost policy schema, CRD model, validation, and chart generation | M | B1 | Invalid profiles/identities are rejected; existing policies remain valid. |
| B3 | Add correlated per-pod intents and agent acknowledgements | M | B1 | Stale evidence cannot acknowledge a new transition; actual allocation is checked. |
| B4 | Resolve boost floors with schedule, emergency, veto, and blackout | M | B2, B3 | Truth-table tests cover crossed profiles, schedule changes, and active floors. |
| B5 | Reconcile admission, exclusivity, grant, completion, and recovery | L | B2, B3, B4 | Durable lifecycle survives retries/restarts and safely cleans partial acquisition. |
| B6 | Ship manual request flow with RBAC, admission, events, and metrics | M | B5 | Authorized request works; cross-pod request/status spoofing fails. |
| B7 | Add index rebuild demo and real Kubernetes acceptance harness | M | B6 | Success, denied growth, expiry, and failed shrink have recorded evidence. |
| B8 | Add authenticated local sidecar boost API | M | B6 | Pod-bound requests, stable IDs, bounded waits, and request limits work. |
| B9 | Add Java lease client and application example | M | B8 | Work starts only after grant; close/retry/timeout semantics are verified. |
| B10 | Document runbooks, compatibility, and publish release artifacts | S | B7, B9 | Installation, operation, failure recovery, and limitations are reproducible. |

## Roadmap

1. Contract and transport: B1, then B2 and B3.
2. Controller integration: B4, then B5 and B6.
3. First usable milestone: B7, manual boost through Kubernetes.
4. Application integration: B8 and B9, then B10.

Main dependency chain: B1 → B3 → B4 → B5 → B6 → B8 → B9 → B10.
B2 must finish before B4; B7 must finish before release. This is a dependency chain,
not a duration-based critical-path estimate. Refine effort after the transport prototype.

## Verification strategy

Use deterministic unit tests for resolution, deadlines, lifecycle transitions, identity,
retry behavior, and quantity comparisons. Inject time. Add concurrency tests for competing
requests and completion/grant races. Use integration tests for CRD/admission behavior and
controller-agent communication. Run the repository's required Maven checks for code changes.

Verify the full path on a supported Kubernetes cluster with cgroup v2 and a supported JVM:

- Acquire, run an index rebuild, complete, reclaim, and retain the same serving pod identity.
- Demonstrate a real deferred/infeasible increase or supported equivalent; work never starts.
- Fail JVM preparation after capacity increases; no grant is issued and cleanup is reconciled.
- Change schedule and emergency targets during an active boost; its floor remains effective.
- Expire or delete a granted request while work continues; no blind shrink occurs.
- Restart controller and agent; reconcile existing state without duplicate permission.
- Restart the application container; stale acknowledgements and grants cannot be reused.
- Make shrink verification fail; expose release blocked and retain the safer allocation.
- Test target deletion, policy edits/deletion, and simultaneous requests.
- Reject cross-namespace, cross-pod, unauthorized completion, and forged acknowledgements.

Measure acquisition latency, operation duration, query latency during rebuild, memory before/
during/after, resize outcomes, and Warden overhead. Do not claim a boost accelerates an
operation without measuring it against a baseline. Document the tested cluster, JVM, and
container runtime; local substitutes do not prove the deployment-specific behavior.

## Observability and runbook

Expose active grants, acquisition outcomes, grant latency, expired grants, release duration,
and blocked releases. Use bounded metric labels; keep individual request/pod identifiers in
status, events, and logs. Every transition includes its request and transition IDs.

The runbook must explain deferred growth, JVM capability failures, uncertain client timeout,
expired work, failed shrink, policy deletion, application restart, and emergency operator
recovery. Document that extra resources may remain allocated until recovery completes.

## Constitution alignment

Reuse existing orchestration ports and resize paths (§2, §6). Add only the components needed
by each milestone (§1). Keep sidecar communication bounded (§4). Preserve verified shrink
and positive confirmation (§5, §7). Verify deployed permissions and runtime behavior on the
actual target environment (§8, §9). Isolate status, telemetry, and intent-write failures (§12).

The existing RSS gate and ordered transition remain necessary controls, not a universal OOM
guarantee. New concurrent allocations and estimation errors remain operational concerns.

## Decisions to validate during implementation

- Confirm a reliable non-heap allowance and effective heap target for boost profiles.
- Select the durable acknowledgement encoding and own-pod write restrictions.
- Confirm controller concurrency support and atomic claim representation.
- Define operator recovery authorization for expired work and deletion finalizers.
- Confirm whether startup readiness and cache pre-warm completion belong in grant criteria;
  current cache hook failures may be isolated and must not imply successful cache warming.

The proposed defaults above permit development in slices. Resolve these details before
shipping the affected contract. GitHub tracking is listed below. This plan does not change the constitution
or implement the feature.

## GitHub tracking

Milestone: [Warden Boost](https://github.com/baokhang83/mnemo-jvm-warden/milestone/10).

| Task | Issue |
| --- | --- |
| B1 | [#105](https://github.com/baokhang83/mnemo-jvm-warden/issues/105) |
| B2 | [#106](https://github.com/baokhang83/mnemo-jvm-warden/issues/106) |
| B3 | [#107](https://github.com/baokhang83/mnemo-jvm-warden/issues/107) |
| B4 | [#108](https://github.com/baokhang83/mnemo-jvm-warden/issues/108) |
| B5 | [#109](https://github.com/baokhang83/mnemo-jvm-warden/issues/109) |
| B6 | [#110](https://github.com/baokhang83/mnemo-jvm-warden/issues/110) |
| B7 | [#111](https://github.com/baokhang83/mnemo-jvm-warden/issues/111) |
| B8 | [#112](https://github.com/baokhang83/mnemo-jvm-warden/issues/112) |
| B9 | [#113](https://github.com/baokhang83/mnemo-jvm-warden/issues/113) |
| B10 | [#114](https://github.com/baokhang83/mnemo-jvm-warden/issues/114) |
| B11 | [#130](https://github.com/baokhang83/mnemo-jvm-warden/issues/130) |

## B1 contract

The detailed proposed lifecycle and API contract is in
[Warden Boost contract](design/0002-warden-boost-contract.md). It defines identity,
grant evidence, safe expiry/deletion, partial-growth cleanup, and an explicit boost
heap target below the container limit. B2–B10 implement and verify this contract.

## B2 schema implementation

Boost definitions, request/status models, generated CRDs, and schema validation are
implemented. See [schema configuration and verification](warden-boost-schema.md).
No runtime grant/reconcile behavior is introduced in B2.

## B3 correlated transport

The shared versioned protocol, per-pod emitter/evidence reader, and agent confirmation
path are implemented. See [correlated transport and verification](warden-boost-protocol.md).
B4/B5 still own floor resolution and durable request/grant reconciliation.

## B8 local API

The authenticated local sidecar Boost API is implemented: a loopback-only HTTP server
(`warden-agent`'s `agent.boost` package) the app container calls to acquire/check/release a
boost, authenticated with a random bearer token shared over an `emptyDir` volume rather than
any Kubernetes credential, satisfying "the SDK does not need Kubernetes credentials." The
agent reuses its existing B6 identity (a Pod cannot run as two ServiceAccounts at once, so a
second identity wasn't possible) with narrowed RBAC and admission (create/update, never
delete, never status, always bound to its own Pod — see
[Enable the manual-flow security package](warden-boost.md#enable-the-manual-flow-security-package)).
See [the application-facing local API](warden-boost.md#application-facing-local-api-b8) for the
wire format.

## B9 lease client

The `warden-boost-client` module and `BoostLease` try-with-resources API are implemented,
wrapping B8's local API in exactly the shape the dev plan specifies. Acquisition blocks until
a durable `Granted` or throws; a terminal non-`Granted` phase throws `BoostRejectedException`
(a definite failure), while the client's own timeout elapsing throws
`BoostAcquisitionTimeoutException` and deliberately never releases the request — the grant may
still be racing server-side, so the stable `requestId` is retained for the caller to retry or
reconcile rather than assumed lost. `close()` requests completion, is idempotent, and does not
block for reclamation. See [the Java lease client](warden-boost.md#java-lease-client-b9) for
the full contract, `examples/.../boost/IndexRebuildExample.java` for the application example,
and `deploy/verify-boost-client.py` for the real-cluster check that a client-side timeout
genuinely never releases an uncertain request.

## B11 G1 support

G1 is now a boost-eligible collector. `CorrelatedResize` previously rejected any JVM without a
live soft max (`GcCapabilities.supportsSoftMax()`), which excluded G1 unconditionally; it now
enforces only the launch-time `-Xmx` ceiling that already applied to ZGC/Shenandoah, and reads
back preparation evidence from a soft-max readback only when the collector has one — for G1,
the evidence is the allocation transition itself, since there is nothing to push live. The
shrink/release path (`ShrinkSequence`, `DeepGc`) needed no change: both were already
collector-blind and `DeepGc`'s forced `GC.run` + committed-size polling already covers G1. The
real gap this leaves, documented in [the Boost guide](warden-boost.md#prerequisites-for-a-boost-evaluation):
a G1 workload must launch with `-Xmx` already covering its largest `heapTarget`, since G1 has
no runtime knob to raise that ceiling on demand the way ZGC/Shenandoah's `SoftMaxHeapSize`
does. Tracked as [#130](https://github.com/baokhang83/mnemo-jvm-warden/issues/130).
