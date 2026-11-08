# Warden Boost: experimental operator guide

Warden Boost temporarily holds extra memory for a specific operation on one running Pod and
container. For example, an operator might prepare headroom before starting an index rebuild,
then request release when the rebuild and its child work have finished. Boost works alongside
the normal schedule: Warden combines the admitted floor with the current scheduled and
emergency targets and keeps the existing shrink guard and blackout rules.

**Status:** the controller lifecycle and correlated agent protocol are implemented, and full
deployed JVM acceptance is verified
([B7](https://github.com/baokhang83/mnemo-jvm-warden/issues/111)). The chart can opt into scoped
admission, RBAC roles, events, and metrics for the manual Kubernetes flow. A pod-bound,
authenticated [application-facing local API](#application-facing-local-api-b8) lets the app
container itself acquire/release a boost
([B8](https://github.com/baokhang83/mnemo-jvm-warden/issues/112)), and the
[`warden-boost-client` Java lease client](#java-lease-client-b9)
([B9](https://github.com/baokhang83/mnemo-jvm-warden/issues/113)) wraps that API in the
try-with-resources `BoostLease` shape application code actually uses. Treat the manual flow
below as an evaluation for a trusted operator in a controlled cluster, not as a
production-ready multi-tenant interface.

If you only use scheduled right-sizing, you do not need a `boosts` section, a `WardenBoost`
request, a policy opt-in label, or Boost acknowledgement permissions for the sidecar. Keep the
existing [installation and sidecar setup](../README.md#getting-started). Controller upgrades
still need the CRD and RBAC updates described below.

## What happens

1. An operator defines a named Boost in the `WardenPolicy` and creates a `WardenBoost` for an
   authorized Pod UID and container name.
2. The controller checks the request and claims the Pod. It snapshots the policy identity,
   resource floor, heap target, and deadlines. Only one Boost can be active per Pod.
3. The existing policy writer resolves the floor with the schedule and emergency profile. The
   agent applies the correlated intent and publishes an acknowledgement for the exact Pod,
   container instance, and transition.
4. The controller records `Granted` only after matching evidence is present, before the
   acquisition deadline, and outside blackout. The operator can then start the work, subject
   to the identity and deadline checks below.
5. After the work **and all child work** finish, the operator sets `completionRequested: true`.
   Warden recomputes the current policy target and performs the existing verified release
   sequence. `Completed` records release evidence; claim cleanup follows reconciliation.

Expiry is not an interruption mechanism: it does not stop Java code. An expired grant retains
its floor until completion or recovery. A deletion request is not proof the work stopped;
the active request's finalizer holds it for safe cleanup.

## Upgrading an existing installation

The controller now watches both `WardenPolicy` and `WardenBoost` resources. Even if you only
use schedules, make both CRDs available before starting the updated controller. Helm installs
files from a chart's `crds/` directory on a new installation but does **not** upgrade installed
CRDs. From a checkout matching the controller/chart version you intend to install:

```bash
kubectl apply -f charts/warden/crds/wardenpolicy-crd.yaml
kubectl apply -f charts/warden/crds/wardenboost-crd.yaml
WARDEN_HELM_RELEASE=warden
WARDEN_HELM_NAMESPACE=default
helm upgrade "$WARDEN_HELM_RELEASE" ./charts/warden --namespace "$WARDEN_HELM_NAMESPACE" --reuse-values
```

Set the release and namespace variables to match your installation; `--reuse-values` retains
the release's existing Helm values. If using
the published chart, inspect its CRDs with `helm show crds warden/warden --version <chart-version>`
and apply those matching CRDs before upgrading that chart. Check your CRD upgrade process and
permissions first. The current policy schema limits policies to 64 profiles and 32 Boost
definitions, and profile request/limit strings to 64 characters; review existing policies
before applying the new schema.

Upgrade the controller RBAC with the chart as well: it now watches and patches WardenBoosts and
their status, in addition to the existing policy and Pod permissions. The Helm controller uses
one replica and a `Recreate` strategy, so an upgrade briefly stops the controller. New Boost
admission and grant require that topology. Custom controller deployments must supply
`WARDEN_CONTROLLER_DEPLOYMENT` and `WARDEN_CONTROLLER_NAMESPACE`; see
[controller configuration](configuration.md#warden-controller-configuration).

A chart version and an image version are separate. Use controller **and** agent images built
from a revision containing the B5 lifecycle; an older image, including an older image cached under
`latest`, does not gain Boost behavior just because the chart or CRDs were updated. The existing
sidecar permissions for scheduled-only resizing remain unchanged.

## Prerequisites for a Boost evaluation

- A working Warden-managed Pod with the normal sidecar, in-place resize support, and cgroup v2.
  Follow the [existing sidecar guide](../deploy/README.md#example-sidecaryaml--warden-as-a-native-sidecar)
  first; Boost does not replace its JMX, process namespace, cgroup mount, or resize requirements.
- A target JVM whose runtime supports `SoftMaxHeapSize`, currently ZGC or Shenandoah in Warden.
  The correlated agent rejects unsupported JVMs or a heap target above launch-time `-Xmx`.
  G1 remains supported by the existing scheduled path, but not by this explicit Boost heap
  preparation. Runtime support and available node capacity must be verified on the target setup.
- A current controller and agent image with Boost support. The target container name in the
  request must match the sidecar's `WARDEN_TARGET_CONTAINER_NAME`.
- The agent must be able to **patch its own Pod's metadata** to publish
  `warden.mnemo.io/resize-ack`, in addition to the scheduled setup's existing `pods` get and
  `pods/resize` patch permissions. The current example sidecar Role grants `pods` get only;
  it is not by itself sufficient for Boost. Kubernetes RBAC cannot limit a Pod patch to one
  annotation. Scope the permission to the target Pod where possible, restrict who can use the
  credential, and account for its extra privilege. For dynamically named replicas, a broad
  `pods` patch grant needs an appropriate admission boundary; RBAC alone is not self-Pod
  enforcement.
- Only trusted operators should create or complete requests or modify policy, target labels,
  claims, and status. Protect agent acknowledgements from forgery and restrict Pod patch
  access. Use the chart's opt-in admission policies described below. The older standalone
  [acknowledgement admission example](../deploy/warden-boost-ack-admission-policy.yaml) is a
  B3 test fixture: its acknowledgement-writer rule can block controller cleanup. Do not install
  it alongside the chart's B6 policies or apply it blindly to an existing cluster.

A Pod service-account token can be visible to more than one container depending on the Pod
configuration. Do not assume that sharing a Pod or using loopback authenticates the app or
protects the sidecar's credentials. See the [threat model](threat-model.md) and
[hardening guide](hardening.md).

### Legacy static-Pod evaluation

For a single, statically named test Pod, this **additional** Role grants its existing agent
service account Pod patch access. Change the namespace, Pod name, and service-account name to
match your test; the account must be the one actually used by the agent. Keep the existing
scheduled-resize Role and binding in place. This grants access to patch the Pod as a whole,
not just its acknowledgement, so use it only in a controlled environment.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: reporting-boost-ack
  namespace: example
rules:
  - apiGroups: [""]
    resources: ["pods"]
    resourceNames: ["reporting-0"]
    verbs: ["patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: reporting-boost-ack
  namespace: example
subjects:
  - kind: ServiceAccount
    name: reporting-agent
    namespace: example
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: reporting-boost-ack
```

This static name restriction does not solve per-replica authorization for a Deployment or
StatefulSet, and it does not keep a shared Pod credential away from the app container. Prefer
the scoped chart security package below for the manual Boost flow.

## Enable the manual-flow security package

The security package is **opt-in** and requires Kubernetes `ValidatingAdmissionPolicy` support
(Kubernetes 1.30 or later; Warden's existing in-place resize requirements still apply). Render and
review the chart before enabling cluster-scoped admission resources. The chart does not change
the existing scheduled-only sidecar Role or install any new policy by default.

```bash
helm upgrade "$WARDEN_HELM_RELEASE" ./charts/warden --namespace "$WARDEN_HELM_NAMESPACE" \
  --reuse-values --set boost.security.enabled=true --set boost.observability.enabled=true
```

Use controller and agent images containing this B6 implementation. `boost.security.enabled`
installs two ClusterRoles and namespace-scoped policy bindings; it does not create service
accounts or bind roles into workload namespaces. `boost.observability.enabled` independently
exposes events and a ClusterIP metrics Service. Choose the namespace selector and dedicated
service-account names in chart values before installing. The default selector is the namespace
label `warden.mnemo.io/boost-enabled=true`; the default names are `warden-boost-agent` and
`warden-boost-operator`, and the default agent init-container name is `warden`
(`boost.security.agentContainerName`). A nonempty `matchLabels` selector is required. Namespace labels must be
controlled by trusted cluster administrators. Ensure selectors for separate Warden releases do
not overlap: their controller identities are different and overlapping policies can block each
other's updates.

Before labeling a namespace, inventory any existing Pods that use the configured agent service
account: once selected, their Pod updates must satisfy the new token-mount policy. In each
participating namespace, **label the namespace and verify the policies are active before
binding either role**. Replace `<chart-fullname>` with the rendered name prefix, for example
`warden` for a release named `warden` or `my-release-warden` for a release named `my-release`.

```bash
WARDEN_NS=example
WARDEN_CHART_FULLNAME=warden
kubectl label namespace "$WARDEN_NS" warden.mnemo.io/boost-enabled=true
for policy in boost-agent-token boost-agent-pod boost-ack-owner boost-agent-resize boost-request-guard; do
  kubectl get validatingadmissionpolicy "$WARDEN_CHART_FULLNAME-$policy" -o yaml
  kubectl get validatingadmissionpolicybinding "$WARDEN_CHART_FULLNAME-$policy" -o yaml
done
# Confirm each policy's observed generation and type-checking status before continuing.
kubectl -n "$WARDEN_NS" create serviceaccount warden-boost-agent
kubectl -n "$WARDEN_NS" create serviceaccount warden-boost-operator
kubectl -n "$WARDEN_NS" create rolebinding warden-boost-agent \
  --clusterrole="$WARDEN_CHART_FULLNAME-boost-agent" --serviceaccount="$WARDEN_NS:warden-boost-agent"
kubectl -n "$WARDEN_NS" create rolebinding warden-boost-operator \
  --clusterrole="$WARDEN_CHART_FULLNAME-boost-operator" --serviceaccount="$WARDEN_NS:warden-boost-operator"
```

The role bindings are namespaced, even though the reusable roles and admission policies are
cluster-scoped. Set `WARDEN_CHART_FULLNAME` to the rendered prefix before running the commands. Check that each
policy's `status.observedGeneration` matches `metadata.generation` and that its type-checking
warnings are empty; also confirm the bindings select the intended namespaces. The operator identity can
create and complete requests for authorized Pods in its namespace; it is a **trusted namespace
operator**, not an application identity. Use a kubeconfig or authentication mechanism for that
service account, provisioned according to your cluster's credential practices. Do not bind these
roles to other identities without equivalent admission protections. The agent identity may
create and update a Boost request bound to its own Pod (B8's local API does this on the app's
behalf), but may never delete one and may never write status; the policy allows only the
configured controller to write Boost status, and rejects cross-Pod requests made with other
Pod-bound tokens, including the agent's own. A dedicated agent
with a Pod-bound token can patch only its own bounded acknowledgement and resize only its own
Pod; it does not restrict which container or resource fields in that Pod may be resized. The
controller may remove an acknowledgement only while clearing its claim and intent. Agent Pods must disable automatic service-account token mounting and mount projected
service-account token volumes into the configured Warden init container, not app or other init
containers.

RBAC does not constrain GET/LIST by the requesting Pod identity; the agent role can read Pods in
its bound namespace. The policies do not protect against a compromised trusted controller or
cluster administrator, or untrusted principals with independently granted broad permissions to
modify policies, Pod labels, claims, or permissions. In particular, Kubernetes RBAC alone does
not prevent a user who can create Pods or workload templates from selecting the dedicated agent
service account and running a different image as the configured init container. Restrict who
can create or change those workloads, create service-account tokens, or alter RoleBindings and
namespace labels. These policies are not a boundary against a malicious workload creator; see
[hardening](hardening.md) and [RBAC mapping](rbac-mapping.md).

The agent needs a **Pod-bound token available only to the trusted agent container**. Set the Pod's
`serviceAccountName` to the configured agent account, disable automatic token mounting, and mount
a projected token, cluster CA, and namespace only in the sidecar. For example, merge the following
pieces into your existing Pod template and its Warden sidecar; keep its existing cgroup mount,
JMX settings, and other required configuration:

```yaml
spec:
  serviceAccountName: warden-boost-agent
  automountServiceAccountToken: false
  volumes:
    - name: warden-api-credentials
      projected:
        sources:
          - serviceAccountToken:
              path: token
              expirationSeconds: 3600
          - configMap:
              name: kube-root-ca.crt
              items:
                - key: ca.crt
                  path: ca.crt
          - downwardAPI:
              items:
                - path: namespace
                  fieldRef:
                    fieldPath: metadata.namespace
  initContainers:
    - name: warden
      # Merge this mount into the existing sidecar; do not replace its other fields.
      volumeMounts:
        - name: warden-api-credentials
          mountPath: /var/run/secrets/kubernetes.io/serviceaccount
          readOnly: true
```

The chart's admission policy requires `automountServiceAccountToken: false` and checks that
projected service-account token volumes are not mounted into application or other init containers.
It does not prevent access through other credentials, shared process inspection, `pods/exec`,
or ephemeral containers. Do not mount this volume into the app. A shared process namespace,
root app container, or other permission to inspect the sidecar can still expose credentials;
follow the existing hardening requirements. Changing a workload template can trigger a rollout. Existing scheduled-only Pods
need not adopt this Boost-specific setup.

## Configure an approved Boost

The following is a **complete policy example** for a controlled test Pod. The quantities and
schedule are examples: choose values for your JVM's actual working set, node capacity, and
non-heap memory. In an existing policy, add the named profile and `boosts` entry to your own
schedule; do not replace a production schedule with this example.

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenPolicy
metadata:
  name: reporting
  namespace: example
spec:
  targetRef:
    apiVersion: v1
    kind: Pod
    name: reporting-0
  timezone: UTC
  profiles:
    normal:
      request: 512Mi
      limit: 1Gi
    maintenance:
      request: 1Gi
      limit: 2Gi
  schedule:
    - cron: "* * * * *"
      profile: normal
  boosts:
    index-rebuild:
      profile: maintenance
      heapTarget: 1Gi
      acquisitionTimeout: 2m
      maxDuration: 20m
```

The Boost's `heapTarget` is an explicit JVM soft target. It must be positive, below the
maintenance profile's `limit`, and no higher than the actual JVM's launch-time `-Xmx`. Leave
enough memory for non-heap use. The profile must have positive integral-byte `request` and
`limit` with request no greater than limit. The controller also accepts an optional
`profiles.<name>.heapTarget` for the policy's correlated schedule/emergency target. If omitted,
a larger container allocation alone does not raise the JVM soft maximum.

`acquisitionTimeout` starts at request creation; `maxDuration` starts when the grant is
recorded. Use Kubernetes duration strings such as `2m` and `20m`. The controller rejects or
holds requests when it cannot validate the target, admission, schedule, deadline, or evidence;
a request does not reserve node capacity or guarantee an in-place increase will succeed.

## Request and complete a Boost

For this example, create the policy in namespace `example`, select the actual Pod, and label it
with the **current policy UID**. The UID changes if the policy is recreated. A label on one
running Pod does not automatically follow a Deployment or StatefulSet replacement; manage
opt-in for each replacement through your workload's deployment process. Changing a workload's
Pod template may itself cause a rollout.

```bash
WARDEN_NS=example
WARDEN_POD=reporting-0
WARDEN_POLICY=reporting
WARDEN_POLICY_UID=$(kubectl -n "$WARDEN_NS" get wardenpolicy "$WARDEN_POLICY" -o jsonpath='{.metadata.uid}')
WARDEN_POD_UID=$(kubectl -n "$WARDEN_NS" get pod "$WARDEN_POD" -o jsonpath='{.metadata.uid}')
printf 'Pod UID: %s\n' "$WARDEN_POD_UID"
kubectl -n "$WARDEN_NS" label pod "$WARDEN_POD" "warden.mnemo.io/policy-uid=$WARDEN_POLICY_UID"
```

Create a request using those **actual** Pod name and UID values. The API defaults
`completionRequested` to `false`; request identity fields are immutable. Reusing the same
request name is not a way to retarget it to a replacement Pod.

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenBoost
metadata:
  name: index-rebuild-001
  namespace: example
spec:
  policyRef: reporting
  podRef:
    name: reporting-0
    uid: "<actual-pod-uid>"
  container: app
  boost: index-rebuild
  completionRequested: false
```

If the label already names a different policy UID, the command fails; investigate the existing
ownership before changing it. Replace the request's UID placeholder with the printed value of
`WARDEN_POD_UID`, save the file, and apply it:

```bash
kubectl create -f boost-request.yaml
kubectl -n "$WARDEN_NS" get wardenboost index-rebuild-001 -o yaml
kubectl -n "$WARDEN_NS" get pod "$WARDEN_POD" -o jsonpath='{.metadata.uid}{"\n"}{.status.containerStatuses[?(@.name=="app")].containerID}{"\n"}'
```

**Do not start work from request creation, Pod annotations, `WardenPolicy.status.currentProfile`,
or an acknowledgement alone.** Check that the request's current `status.phase` is `Granted`,
that `status.podUid` and `status.containerId` match the live Pod and application container,
and that the current time is before `status.executionDeadline`. These are separate Kubernetes
reads, not an atomic cross-resource transaction: a restart or replacement can happen after a
check. Do not reuse a grant after an identity change, and coordinate your operation's start and
lifetime accordingly. The local sidecar API and Java client can also manage requests, as
explained below.

Only after the operation and all child work have actually stopped, request completion:

```bash
kubectl -n "$WARDEN_NS" patch wardenboost index-rebuild-001 --type merge \
  -p '{"spec":{"completionRequested":true}}'
kubectl -n "$WARDEN_NS" get wardenboost index-rebuild-001 -o yaml
```

The flag is monotonic and cannot be reset. Completion may be recorded during a blackout, but
resource release waits until it ends. Do not delete or force-remove the finalizer as a shortcut
while work might still be running; doing so bypasses the protection that retains the floor.

## Status and troubleshooting

| Phase or condition | Meaning and action |
| --- | --- |
| `Pending` | Admission has not completed. Check `status.conditions` for blackout, target readiness, or `UnsupportedControllerTopology`. |
| `Preparing` | The floor is admitted; work is not yet authorized. Check node headroom, the agent and its Pod patch permission, and the current transition/acknowledgement. |
| `Granted` | Matching preparation evidence was recorded. Verify the current Pod/container identity and `executionDeadline` before work. |
| `Rejected` | The request was not admitted. Inspect the condition reason, including target opt-in, identity, policy, named Boost, and claim conflicts. |
| `Failed` | Acquisition failed or the policy changed during preparation. No grant was issued; partial preparation may still require guarded cleanup. |
| `Expired` | The execution deadline elapsed. Work may still be running and the floor remains held. Confirm the operation stopped before requesting completion. |
| `Releasing` | Completion or failed-acquisition cleanup is being reconciled. Blackout, failed shrink verification, missing policy, or stale evidence can delay release. |
| `Completed` | Release evidence is recorded, or no resource transition was needed. The controller cleans up the claim separately. |
| `Orphaned` | The bound Pod or container instance changed. The old grant cannot authorize work on its replacement. |
| `UnsupportedJvm` | The agent could not prepare the explicit heap target on this JVM. Check the collector, soft-max capability, and `-Xmx`. |
| `StaleAcknowledgement` / `ReleaseBlocked` | The evidence is for another transition, or release verification failed. Inspect controller and agent logs; do not force a shrink. |

If a client times out or disconnects after submitting a request, the API server may still have
accepted it. Look up the original request by its stable name and inspect its status before
retrying; do not assume a grant or operation could not have raced with the timeout. If an
in-place increase is deferred, inspect Pod resize status, node headroom, and agent logs; work
must wait for a matching grant. An agent or application restart can invalidate prior instance
evidence, so compare the live container ID before any work.

Inspect status with `kubectl ... get wardenboost ... -o yaml`, and inspect the current policy,
Pod UID, container ID, annotations, and controller/agent logs. A missing or recreated policy
can leave release pending; investigate before any operator recovery. If an active Pod
instance has disappeared or an operator chooses force deletion, follow a cluster-specific
recovery procedure and recognize that force-removing a finalizer can bypass Warden's safeguards.

For wire format, status fields, concurrency details, and disposable-cluster verification,
see [Boost schema](warden-boost-schema.md) and [correlated protocol](warden-boost-protocol.md).


## Events and metrics

When `boost.observability.enabled=true`, the controller emits best-effort Kubernetes Events on
phase, readiness-reason, or persisted transition changes, with request and transition IDs in
event messages. Events
are diagnostic and may be lost on a crash; status remains authoritative.

The chart creates `<chart-fullname>-boost-metrics`, a ClusterIP Service exposing `/metrics`
on port 8081 by default (`boost.observability.port` changes it). Restrict access with your
network policy or monitoring infrastructure: the endpoint is unauthenticated. It exposes
`warden_boost_active_grants`, `warden_boost_expired_grants`, and
`warden_boost_blocked_releases` as gauges from current request status;
`warden_boost_transitions_total{phase=...}` counts process-local transitions. Acquisition and
release duration summaries expose `_sum` and `_count` in seconds. Counters and duration sums
reset on controller restart; deleted requests leave the gauges. Request and Pod IDs are not
metric labels. A failed Kubernetes list returns HTTP 503 instead of reporting zero.

For a runnable index rebuild and recorded end-to-end checks on a disposable cluster, see
[Boost B7 acceptance](validation/warden-boost-b7-kind.md).

## Application-facing local API (B8)

The app container can acquire and release a boost itself, over a loopback-only HTTP API the
agent sidecar serves — no `kubectl` or Kubernetes credentials in the app.

**Enable it** by setting, on the `warden` sidecar container (see
`deploy/example-sidecar-boost.yaml` for a complete manifest):

| Variable | Required | Default | Notes |
|---|---|---|---|
| `WARDEN_BOOST_POLICY_REF` | to enable the API | unset | The `WardenPolicy` name to stamp on self-requested boosts. Presence of this one variable is the feature flag — a Pod can't discover its own policy's *name* any other way (only its UID, via the existing resize-intent label), so there's no safe default to fall back to. |
| `WARDEN_POD_UID` | yes, once enabled | unset | Downward API `fieldRef: metadata.uid`. Needed for the request's `podRef.uid`. |
| `WARDEN_BOOST_PORT` | no | `8090` | Loopback-only; never bind this to the Pod IP. |
| `WARDEN_BOOST_ACQUIRE_WAIT_SECONDS` | no | `25` | How long an `acquire` call blocks before returning `202` so the caller polls `/status` instead. |
| `WARDEN_BOOST_TOKEN_PATH` | no | `/var/run/warden/boost/token` | Where the agent writes the shared bearer token (see below). |

The token file is owner-only (`0600`) on POSIX filesystems. The application must be able to
read it: run the application and sidecar with the same effective UID, or use another explicitly
verified credential-sharing arrangement compatible with that mode. A shared volume or `fsGroup`
alone does not grant a different UID access to a `0600` file. Do not make the token world-readable.

This also requires the same RBAC the manual flow's `boost.security.enabled=true` package
grants the agent identity (`wardenboosts` get/list/watch/create/patch — never `delete`, never
the `status` subresource) and the `boost-request-guard` admission policy, which binds any
self-requested boost to the requesting Pod's own identity. Without that chart flag, `acquire`
calls reach the Kubernetes API but are denied by RBAC.

**Authentication.** Loopback binding alone is not an authorization boundary between containers
sharing a pod network — any container in the Pod's network namespace can reach a loopback port.
So every call needs `Authorization: Bearer <token>`, where the token is a random value the
agent generates at startup and writes to a file on an `emptyDir` volume shared with the app
container (not a Kubernetes Secret — no extra API call, and the app needs no Kubernetes
credentials at all). Mount the same volume into both containers; the agent writes
read-write, the app reads read-only:

```yaml
volumes:
  - name: warden-boost-token
    emptyDir: {}
# warden (sidecar) container:
volumeMounts:
  - name: warden-boost-token
    mountPath: /var/run/warden/boost
# app container:
volumeMounts:
  - name: warden-boost-token
    mountPath: /var/run/warden/boost
    readOnly: true
```

The agent is the sole writer and regenerates the token on every restart; it never reads an
existing file back. If the sidecar restarts independently of the app, the app's previously-read
token goes stale — read the token file fresh per call (or at least on a `401`) rather than
caching it for the process lifetime.

**Endpoints** (all require the bearer token above):

- `POST /v1/boost/acquire` — body `{"boost":"<name>","requestId":"<optional>"}`. Omit
  `requestId` for a new request (the server generates one and returns it); supply it back to
  retry idempotently — the same `requestId` never creates a second `WardenBoost`. Blocks up to
  `WARDEN_BOOST_ACQUIRE_WAIT_SECONDS` for `Granted` or a terminal phase: returns `200` if it
  settled in time, `202` with the current phase if the wait elapsed (poll `/status` next). Only
  one request may be outstanding per agent process at a time — a second `acquire` for a
  different, still-active `requestId` gets `409` with the existing one.
- `GET /v1/boost/status?requestId=<id>` — the current phase (`404` if unknown).
- `POST /v1/boost/release` — body `{"requestId":"<id>"}`. Idempotent: releasing an
  already-releasing or already-completed request is a no-op, not an error.

All three respond with `{"requestId":"...","phase":"...","message":"..."}` (`message` may be
`null`). The app never supplies a Pod name, container name, policy name, or any Kubernetes API
path — those come from the sidecar's own configuration, never from the app's request body.

## Java lease client (B9)

`warden-boost-client` wraps the local API above in the shape application code actually wants.
It requires Java 21. The dependency below uses the version of an application release that
contains B9. The client is included in release `0.2.0`; it is not present in the older `v0.1.4`
release. Verify that Maven Central has published the artifact before using it:

```xml
<dependency>
  <groupId>io.github.baokhang83.mnemo</groupId>
  <artifactId>warden-boost-client</artifactId>
  <version>0.2.0</version>
</dependency>
```

To build from source instead, run `mvn -pl warden-boost-client -am install` and use the
checkout's project version locally. The release workflow stamps the Maven version from its tag.

```java
try (BoostLease lease = WardenBoostClient.discover().acquireBoost(
        "index-rebuild", Duration.ofMinutes(2))) {
    rebuildIndex();
}
```

`discover()` reads `WARDEN_BOOST_PORT`/`WARDEN_BOOST_TOKEN_PATH` from the environment, matching
the agent's own defaults — an app container typically needs **no** extra env vars, only the
shared token volume mount (see `deploy/example-sidecar-boost.yaml`). It needs no Kubernetes
credentials: every call stays on the loopback local API.

`acquireBoost` returns only once the request reaches `Granted` — never for a merely `Pending`
one. It can fail two different ways, and they mean different things:

- **`BoostRejectedException`** — the client observed a non-granted phase it treats as terminal.
  This call did not return a lease. Inspect the
  request: failed preparation can still need guarded cleanup, and an expired request can retain
  its allocation until the operation is known to have stopped and completion is requested.
- **`BoostAcquisitionTimeoutException`** — this call gave up waiting before the request
  settled. The outcome is uncertain: the grant may still be racing server-side. The client
  deliberately does not release or otherwise touch the request on this path; it carries
  `requestId()` so the caller can retry acquisition with the same ID later
  (`acquireBoost(boost, requestId, timeout)`) or look it up directly (`inspect(requestId)`) to
  reconcile what actually happened, rather than assuming the operation could not have started.
  The client checks its timeout between HTTP calls, so a call already in flight can make the
  elapsed time exceed that timeout.

`BoostLease.close()` requests completion and is idempotent — call it more than once freely —
and does not block until memory is actually reclaimed; `lease.status()` re-fetches the current
phase if a caller needs that confirmation. Application code should close the lease only after
the boosted operation and its child work finish; there is no async/cancellation convenience
wrapper (deferred, per the dev plan).

See `examples/src/main/java/.../examples/boost/IndexRebuildExample.java` for a complete,
runnable example of both code paths, and `deploy/verify-boost-client.py` for a real-cluster
check that a client-side timeout genuinely never releases the uncertain request.

For the tested matrix, release procedure, and concrete failure-recovery steps, see the
[Boost operations and release runbook](warden-boost-operations.md).
