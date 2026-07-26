# Security review checklist

Every high-risk threat identified in [`threat-model.md`](threat-model.md),
linked to its mitigation, its verifying test (if any), and either "closed" or
the explicit residual risk accepted in its place. This is the artifact a
reviewer should walk line-by-line before signing off on a production
installation — and the list this project itself should re-check before any
release that touches RBAC, JMX, resize, or intent-handoff code.

| # | Threat | Mitigation | Verified by | Status |
| - | --- | --- | --- | --- |
| 1 | Controller compromise → cluster-wide pod read/annotate | No `secrets`/`exec`/`delete`/node grant exists; non-root image | `deploy/verify-rbac-boundaries.sh` (negative checks) | Mitigated; **residual risk accepted** — `patch` on `pods` isn't scoped to annotation-only or same-namespace by RBAC alone. Follow-up: [#97 (W-807)](https://github.com/baokhang83/mnemo-jvm-warden/issues/97) — narrow the PATCH shape with a `ValidatingAdmissionPolicy`. |
| 2 | Malicious/misconfigured `WardenPolicy` (unauthorized `targetRef`) | Agent's own RSS-verification gate bounds blast radius to "wrong schedule," not OOMKill | `deploy/verify-oomkill-safety.sh` (gate holds under adversarial load, independent of intent source) | **Residual risk accepted** — no opt-in check on the target, no RBAC shipped for `wardenpolicies` itself. Follow-up: [#98 (W-808)](https://github.com/baokhang83/mnemo-jvm-warden/issues/98) — require a target-side opt-in label before `IntentEmitter` annotates it. |
| 3 | Target-workload compromise | Sidecar token not mounted into `app`; JMX carries no filesystem/API capability | Code inspection (`Dockerfile`, `example-sidecar.yaml` volume mounts) | Mitigated for the paths Warden controls; inherits whatever the workload's own RBAC/network position already grants (out of scope by definition). |
| 4 | Shared process namespace (root `app` can inspect sidecar) | Sidecar runs as dedicated non-root `warden` user | Not independently verified with a real `ptrace` attempt across mismatched UIDs in a shared PID namespace | **Residual risk accepted**, documented as an operator hardening step (`docs/hardening.md`) rather than something Warden's own manifests can force. Follow-up: [#99 (W-809)](https://github.com/baokhang83/mnemo-jvm-warden/issues/99) — real-cluster check that cross-container `ptrace`/`/proc` inspection is denied when `app` is non-root. |
| 5 | JMX access (unauthenticated MBean server) | `jmxremote.host=127.0.0.1` + `authenticate=false` pair | **Verified against a real cluster** (`deploy/README.md`): cross-pod connection refused, same-pod connection succeeds | Mitigated, *conditional on the target's own launch flags* — Warden has no code-level check that a target actually set the host-binding flag. Follow-up: a startup-time check in `TargetAttacher`/`AttachSupervisor` that the JMX connection is only reachable from loopback (best-effort; can't fully self-verify network reachability from inside; tracked in threat model, not yet an issue — hard to verify meaningfully without a real network path check). |
| 6 | Host cgroup mount (node-wide cgroup read visibility) | Read-only mount; blocked by PodSecurity `restricted`/`baseline` unless exempted | Code inspection + `deploy/README.md`'s explicit cost analysis | **Residual risk accepted** — no narrower mechanism exists in Kubernetes today. Re-evaluate if a future Kubernetes version adds pod-scoped cgroup mount support. |
| 7 | Prometheus/metric spoofing | Prometheus URL is operator-configured, not attacker-reachable via policy content | `deploy/verify-prometheus-metric-source.sh` (happy path only — does not test a spoofed/MITM'd response) | **Residual risk accepted** for the network path to Prometheus itself (no mTLS/auth in `PrometheusMetricSource`). Re-scope when guardrails drive unattended action beyond this milestone's schedule-bounded veto/grow (M7). |
| 8 | Resize API abuse (cross-replica) | `warden-resize-admission-policy.yaml` (#71) | **Verified against a real cluster** (`deploy/verify-cross-pod-resize-denied.sh`) — both negative and positive control | Mitigated, *conditional on the operator applying the policy* — not installed by the Helm chart. Tracked in `docs/hardening.md`. Follow-up: [#102 (W-812)](https://github.com/baokhang83/mnemo-jvm-warden/issues/102) — ship this policy as an opt-in Helm chart value. |
| 9 | Cross-namespace targeting | `IntentEmitter` always uses the policy's own namespace; no `namespace` field exists on `targetRef` | Code inspection; no dedicated regression test | Enforced, but **not covered by an automated test**. Follow-up: [#100 (W-810)](https://github.com/baokhang83/mnemo-jvm-warden/issues/100) — regression test asserting a policy can never resolve/annotate a pod in a different namespace. |
| 10 | Denial of service (attach ambiguity, reconcile/API load) | Fail-safe "stay unattached" on ambiguity; fixed-rate reconcile/poll loops | Not independently verified under load/throttling | **Residual risk accepted** for API-server throttling (`429`) behavior — client-side rate-limiting/backoff not independently verified. This is exactly what `W-801`/`W-803` (sustained-load and failure-injection) under the parent epic exist to close; not re-verified redundantly here. |
| 11 | Secret or log leakage | No `secrets` RBAC grant exists anywhere; logs contain only static/non-secret fields | Code inspection of `AgentLog`, `WardenPolicyReconciler`'s log lines | **Residual risk accepted** for lack of an automated regression test. Follow-up: [#101 (W-811)](https://github.com/baokhang83/mnemo-jvm-warden/issues/101) — test capturing agent/controller stdout during a real resize and asserting the bearer-token pattern never appears. |

## Sign-off

A reviewer using this checklist should be able to say, for each row: "I
understand this residual risk and accept it for my deployment" or "this
follow-up must land before I install this in production." Neither this
checklist nor the threat model it's derived from is a substitute for running
the actual `deploy/verify-*.sh` scripts against a cluster shaped like the
target environment — several rows above are explicitly marked as verified
only on the project's own kind-cluster checks, not on production-shaped
infrastructure (that gap is what `W-801`/`W-802`/`W-803` in the parent epic
exist to close).
