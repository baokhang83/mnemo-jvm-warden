# Warden 0.2.0: Warden Boost release notes

The `0.2.0` release contains the Boost implementation through B10. Maven, GHCR, and Helm
publication use separate workflows; check each registry and workflow before using its artifacts.

## Experimental Boost

Warden Boost temporarily holds an approved memory floor for a named operation on a specific
Pod/container instance. The controller admits and tracks requests; the agent grows resources
and prepares the JVM before a grant, then uses the verified shrink sequence after completion.
Expiry does not stop application work. A failed or uncertain operation can leave capacity held
until it is safe to reclaim.

Operators can use the Kubernetes request flow, or configure the authenticated loopback sidecar
API and Java lease client for an application to request and complete its own Boost. The local
client needs no Kubernetes credential, but the sidecar's identity and token-sharing permissions
are part of the security boundary. Boost is opt-in; scheduled-only users retain their existing
flow. Use compatible controller and agent images, CRDs, chart configuration and client versions.

## Boundaries and validation

Boost requires an in-place-resize-capable Kubernetes environment, a compatible node/runtime,
and a JVM capable of the configured explicit soft heap target. `-Xmx` remains fixed; extra
node capacity and safe reclamation are not guaranteed. One active Boost per Pod is supported;
CPU Boosts and distributed coordination are outside the initial scope. Read the
[operations runbook](../warden-boost-operations.md) before deploying.

The repository records a real manual-flow Kubernetes/ZGC acceptance run. Local API security and
Java-client timeout behavior were tested separately; the evidence does not establish one
end-to-end Java-client-to-real-grant-and-reclaim run or production performance. Release checks
must confirm each published Maven artifact, image and chart independently.
