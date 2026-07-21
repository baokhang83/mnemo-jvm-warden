# Session: Slice 3: TargetLocator /proc rewrite + deploy manifest + real cluster verification

- **intent:** Slice 3: TargetLocator /proc rewrite + deploy manifest + real cluster verification
- **started:** 2026-07-21

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

- **`TargetLocator` rewritten to scan `/proc` directly** — replaced `VirtualMachine.list()` entirely; a real deployment showed the "fixed" attach code still never reached Ready, because `jps`/`VirtualMachine.list()`'s `hsperfdata`-based discovery is gated by the exact same cross-container restriction as the old Attach API, one step earlier in the pipeline than the piece actually fixed. `/proc/<pid>/comm == "java"` is unaffected by it (confirmed via direct test). `TargetAttacher`/`AttachSupervisor` simplified alongside it: `Optional<VirtualMachineDescriptor>` became `Optional<Long>` throughout, since nothing downstream ever used anything but the PID. · status: documented
- **`deploy/example-sidecar.yaml`'s `app` container** — launched via `java -m jdk.httpserver/sun.net.httpserver.simpleserver.JWebServer ...` instead of the `jwebserver` binary, since that binary was confirmed to not honor `JDK_JAVA_OPTIONS` (no "Picked up..." message, port never opened) the way a plain `java` launch does — needed so the required JMX flags actually reach the target JVM. · status: documented
- **A second real timing race, caught by running the suite repeatedly** — `SpawnedJvm.awaitReady()`'s JMX-port check was a raw TCP `Socket.connect()`, which succeeds the instant the OS accepts the connection, before the RMI registry has actually finished exporting its object table. Under sequential tests reusing the same fixed port, this surfaced as `NoSuchObjectException: no such object in table` (a stale reference from the just-replaced previous target). Fixed by making the readiness probe a full `JMXConnectorFactory.connect()` + close, the only check that actually proves the *new* registry is live. Verified fixed by running the full suite 3x clean, not just once. · status: documented
- **Real end-to-end proof, not just unit tests** — deployed the actual rebuilt image to a real kind cluster with genuinely mismatched UIDs (999/0): pod reached `2/2 Ready` for the first time in this whole investigation, agent logged `"attached to target pid 29"`, `/readyz` returned `READY`. Re-verified the loopback-only security property on this real deployment (cross-pod connect refused). Verified `GcDetector`/`SoftMax` operate correctly over the new connection end-to-end (target ergonomically selected Serial GC under the container's memory limit; `SoftMax` correctly rejected it as unsupported — the mechanism working as designed, not a bug). · status: documented


## Decision: rewrite TargetLocator to scan /proc directly, dropping VirtualMachine.list() entirely

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/attach/TargetLocator.java`
- **why:** this bug's own design claimed TargetLocator was unaffected by the UID-mismatch problem, reasoning that /proc/PID/comm reads don't cross mount namespaces; that reasoning was correct but untested, and deploying the fixed attach code end-to-end showed the pod still never reached Ready, because VirtualMachine.list() (what TargetLocator actually called) discovers candidates via each JVM's hsperfdata file, which lives in that JVM's own private /tmp, the identical cross-container restriction that broke the Attach API, one step earlier in the same pipeline
- **alternative:** keep VirtualMachine.list() and try to work around its hsperfdata dependency some other way — rejected: there is no workaround for a mechanism that fundamentally reads a file in a different container's private filesystem; plain /proc/PID/comm reads, confirmed unaffected by the UID mismatch, replace the need for it entirely
- **design:** ../design.md#sequence-connect-over-the-targets-own-jmx-port-loopback-only
- **constitution:** §9
- **trust:** ✓ verified
