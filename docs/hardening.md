# Operator hardening guide

Actionable steps for operators deploying Warden, distilled from
[`threat-model.md`](threat-model.md). That document explains *why*; this one
is the checklist to actually run through before going to production.

## Required for the trust model to hold

- [ ] **JMX flags on every target JVM** are the exact six flags in
  `deploy/README.md`, especially `jmxremote.host=127.0.0.1`. Verify the
  binding yourself (attempt a connection from a second pod) rather than
  trusting the launch command alone — a target that drops that one flag
  turns `authenticate=false` into unauthenticated, cluster-reachable MBean
  access. See threat model §5 ("JMX access").
- [ ] **`app` containers run non-root** (`runAsNonRoot: true` /  explicit
  `runAsUser`) wherever `shareProcessNamespace: true` is set. A root `app`
  container can inspect (and potentially `ptrace`) the Warden sidecar's
  process in a shared PID namespace. See threat model §4 ("Shared process
  namespace").
- [ ] **`warden-resize-admission-policy.yaml` (#71) is applied** for any
  `Deployment`/`StatefulSet`-targeted installation. It is *not* installed by
  the Helm chart automatically — without it, any replica's sidecar token can
  resize a different replica. See threat model §8 ("Resize API abuse").
- [ ] **`hostPath` exemption** is granted if your cluster enforces
  PodSecurity `restricted`/`baseline` — the sidecar's cgroup mount needs it,
  and there is no verified narrower alternative. See threat model §6 ("Host
  cgroup mount").

## Recommended

- [ ] **`NetworkPolicy`** scoping who can reach a Warden-managed pod's
  `:8080` (`/healthz`, `/readyz`, `/metrics`) — unauthenticated, bound to all
  interfaces in the pod's network namespace. No secrets are exposed there,
  but least-exposure still applies. See threat model, "Network exposure."
- [ ] **Secure the path to Prometheus** (mTLS via a service mesh, or a
  `NetworkPolicy`) if `guardrail.metric` results influence anything you treat
  as safety-critical — `PrometheusMetricSource` does not authenticate or
  verify the integrity of the Prometheus response itself. See threat model
  §7 ("Prometheus / metric spoofing").
- [ ] **Restrict `wardenpolicies` write access** and, if multiple teams share
  a namespace, restrict who can target each other's workloads — this
  project does not ship RBAC for the `WardenPolicy` resource itself, nor
  does it require a target to opt in to a given policy's `targetRef`. See
  threat model §2 ("Malicious or misconfigured WardenPolicy").
- [ ] **Restrict `pods/exec`** into the `warden` sidecar container the same
  way you would restrict access to the sidecar's own Kubernetes token — the
  shipped image bundles harness/test entry points (`CrossPodResizeAttempt`
  and friends) that can drive a real resize using that token. See threat
  model, "Out-of-scope assumptions."

## Verify, don't assume

Run [`../deploy/verify-rbac-boundaries.sh`](../deploy/verify-rbac-boundaries.sh)
against your own cluster (or a kind cluster with your Helm values) after any
RBAC-affecting change, and re-run
[`../deploy/verify-cross-pod-resize-denied.sh`](../deploy/verify-cross-pod-resize-denied.sh)
if you're targeting `Deployment`/`StatefulSet` workloads, to confirm the
admission policy is actually enforced in your cluster, not just applied.
