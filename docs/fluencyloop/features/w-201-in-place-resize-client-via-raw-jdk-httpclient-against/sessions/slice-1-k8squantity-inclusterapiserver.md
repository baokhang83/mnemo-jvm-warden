# Session: Slice 1: K8sQuantity + InClusterApiServer

- **intent:** Slice 1: K8sQuantity + InClusterApiServer
- **started:** 2026-07-20

<!--
FluencyLoop Stage 3 — a session is a slice of the build. It holds two persistent records:

1. Knowledge transfer — what the developer was actually made fluent in during this slice.
2. Decisions — the genuine forks, one `## Decision:` block each.

Both are appended at the slice boundary, from the *live* teaching. One bullet per field, so it
renders one-per-line (plain `key: value` lines collapse into a paragraph when rendered). No
`commits:` field: the feature is a branch, so the PR view derives commits live from git.

DECISION fields:
  where        — file/area the decision lives in (NOT a line number — survives refactoring)
  why          — the rationale, taught live before it was written
  alternative  — the rejected option and why (this is what makes it rationale, not description)
  design       — (optional) ../design.md#anchor — the diagram this decision shaped or used
  constitution — (optional) §N — the principle this decision serves or trades off against
  trust        — ✓ verified | ⚠ not independently verified  (about the DECISION, never the person)

KNOWLEDGE-TRANSFER fields (one bullet per component/role/mechanism explained):
  subject      — the component, role, or mechanism (e.g. a class, an agent, a rule)
  what         — what it does, and under what conditions it does it
  status       — documented (captured here) | follow-up (worth covering later)
  Describe the WORK, never a person: no competence, no prior-knowledge, no "who learned what".
  These files are committed and name an identifiable author via git — keep them GDPR-safe.

Delete this comment and the examples below once real content lands.
-->

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`K8sQuantity`** — parses Kubernetes resource `Quantity` strings (plain integer, binary Ki/Mi/Gi/Ti/Pi/Ei, decimal k/M/G/T/P/E suffixes) into byte counts. Exists because the API server normalizes memory quantities in status to a different string than what was PATCHed for the same value — verified against a real target. Test fixtures use the exact real values captured from that spike. · status: documented
- **`InClusterApiServer`** — reads `KUBERNETES_SERVICE_HOST`/`KUBERNETES_SERVICE_PORT` env vars and the mounted service-account volume (`token`, `ca.crt`, `namespace` under `/var/run/secrets/kubernetes.io/serviceaccount`) to give `PodResizeClient` everything it needs to call the API server as itself, with no explicit RBAC/volume wiring beyond what the pod spec already provides by default. · status: documented
- **`SelfSignedCert`** — test-only helper generating a throwaway self-signed cert via the JDK's `keytool` subprocess, since there's no public JDK API for certificate generation and pulling in a crypto library just to test trust-store wiring would be heavier than the thing being tested. · status: documented


## Decision: re-read the bearer token from disk on every call, never cache it

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/resize/InClusterApiServer.java`
- **why:** a bound service-account token is time-limited (confirmed via a real token's expirationSeconds: 3607, about an hour) and the kubelet rotates the file in place before it expires; for a long-lived sidecar, caching the token at startup would mean it eventually starts sending an expired one and getting rejected
- **alternative:** read the token once at startup and cache it in a field — rejected: works until the first rotation, then silently breaks every subsequent call until the process restarts
- **trust:** ⚠ not independently verified
