# Security Policy

Warden runs inside your cluster with real privileges: it reads and patches Pod
objects, resizes containers in place, and (per pod) reaches into a target
JVM's JMX port and the node's cgroup filesystem. Treat anything that
undermines those boundaries as a security bug, not a regular bug.

See [`docs/threat-model.md`](docs/threat-model.md) for what those boundaries
are, [`docs/rbac-mapping.md`](docs/rbac-mapping.md) for exactly which
Kubernetes permission maps to which code path, and
[`docs/security-review-checklist.md`](docs/security-review-checklist.md) for
how each threat below is tracked to a mitigation, a test, or an accepted risk.

## Supported versions

Warden is pre-1.0 (`0.x`, CRD version `v1alpha1`). There is one supported
line: **the latest published release.** Security fixes land on `main` and go
out in the next `0.x` release; older `0.x` releases do not get backports.
This will change once the project reaches a `1.0`/stable CRD version, at
which point this table will define a real support window.

| Version | Supported |
| ------- | --------- |
| latest `0.x` release | ✅ |
| anything older | ❌ |

## Reporting a vulnerability

**Do not open a public GitHub issue for a suspected vulnerability.**

Use GitHub's private vulnerability reporting instead:

1. Go to the [Security tab](https://github.com/baokhang83/mnemo-jvm-warden/security) of this repository.
2. Click **"Report a vulnerability"**.
3. Describe the issue: affected component (controller / agent / Helm chart),
   the privilege or trust boundary it crosses, reproduction steps, and impact.

This opens a private draft advisory visible only to you and the maintainer —
no separate email address needed, and it keeps the report out of the public
issue tracker until a fix is ready.

If you can't use that flow for some reason, open a regular issue that says
only "I have a security report I can't file privately" with no technical
detail, and the maintainer will follow up to arrange a private channel.

## Response process

This is a solo-maintained project, not a project with a security team or a
contractual SLA — the timelines below are a best-effort target, not a
guarantee:

- **Acknowledgment:** within 5 business days of a report.
- **Triage:** within 10 business days, confirming whether it's a real
  vulnerability, its severity, and which component(s) are affected.
- **Fix:** timeline depends on severity and complexity; a critical issue
  (e.g. anything that lets one pod escalate to another pod's or the cluster's
  privileges) is prioritized over feature work.
- **Disclosure:** coordinated with the reporter. Once a fix is released, a
  GitHub Security Advisory is published crediting the reporter (unless they
  ask to stay anonymous), with a CVE requested for anything above low
  severity.

## Security update policy

- Fixes are released as a new `0.x` version; there is no backport to prior
  versions while the project is pre-1.0.
- Release artifacts (JARs, container images, Helm chart) are signed and
  published with SBOM/provenance evidence per [`docs/rbac-mapping.md`](docs/rbac-mapping.md)'s
  companion supply-chain work (see the `W-805` ticket) — verify those before
  deploying a release you didn't build yourself.
- Dependency vulnerabilities (CVEs in transitive JARs, base images) are
  tracked the same as any other reported vulnerability above; there is
  currently no automated dependency-scanning bot wired into CI (tracked as a
  known gap, not a silent assumption — see the review checklist).
