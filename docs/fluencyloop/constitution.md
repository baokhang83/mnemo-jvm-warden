# Constitution

**Project:** mnemo-jvm-warden
**Ratified:** 2026-07-12

Short by design. Every feature's decisions (Stages 2–4) are checked against these
principles, so each is written to be *checkable* — you can point at a code decision and
say whether it holds.

## Principles

### §1 — Simplicity first (YAGNI)

Build the simplest thing that satisfies the current slice. No abstraction, interface,
config flag, or generality without a present caller that needs it. *Prevents* speculative
flexibility and dead code that cost more than the value they were meant to add. *A decision
violates this when* it adds an interface with one implementation and no second caller now,
or a knob nothing reads.

### §2 — Depend on abstractions at the volatile seams (SRP + DIP)

Each type has one reason to change. Safety and orchestration logic depends on narrow ports
(e.g. `HeapController`, a resize port), never directly on a concrete GC driver or the
Kubernetes client. *Prevents* the core state machine being welded to ZGC or Fabric8, which
would make supporting a new collector or client a rewrite. *A decision violates this when*
the shrink/grow logic imports a concrete GC or K8s type instead of a port.

### §3 — Small, intention-revealing units (Clean Code)

Functions and classes stay small and single-purpose; names state intent; comments explain
*why*, not *what*. No single method that releases, verifies, and resizes in one blob.
*Prevents* unreadable safety-critical code where a reviewer cannot see the ordering. *A
decision violates this when* a unit spans multiple concerns, or a name needs a comment to
decode.

### §4 — The agent earns its footprint (efficiency)

Warden is a memory-efficiency product; its own sidecar must be lean — no busy-polling, no
unbounded buffers, no heavyweight dependency on the hot path. Overhead must stay
proportionate to the savings delivered. *Prevents* the irony of a right-sizer that wastes
the memory it reclaims. *A decision violates this when* it adds a tight poll loop, a heavy
dependency to the sidecar, or an allocation on a per-tick path.

### §5 — No unverified shrink (safety invariant)

Any operation that lowers a cgroup limit or memory request is gated on a verified
precondition — RSS confirmed below the target — and the ordering invariants
(shrink → verify → resize; grow → resize → raise) are enforced in code, not by convention.
*Prevents* OOMKilling a live pod, the one failure that makes Warden worse than doing
nothing. *A decision violates this when* a resize-down is issued without a preceding RSS
gate, or when ordering is left to caller discipline.

### §6 — Recovery reuses the normal path (no shadow retry logic)

A retry, reconnect, or abort path is the same code as the path it recovers from, re-entered,
not a parallel implementation of it. *Prevents* the recovery path silently drifting out of
sync with the happy path it's supposed to mirror &mdash; the exact place a safety-critical
system tends to rot unnoticed, since the recovery path runs rarely and gets tested even less.
*A decision violates this when* "handle the failure" and "do the normal thing" are two
different blocks of logic that could disagree about what happens next.

### §7 — A verification poll needs positive evidence, not absence of change

A poll that waits for a state transition (uncommit, RSS drop, resize completion) may only
declare success once it has observed the transition actually happen. "No change since the
last sample" is never itself proof of settling &mdash; it is indistinguishable from "the
transition hasn't started yet." *Prevents* a false-positive completion that trusts silence
instead of evidence, which is exactly what let W-104's first uncommit check report
`completed: true` after ~750ms of nothing happening, four seconds before the real work would
even begin. *A decision violates this when* a stability/completion check can be satisfied by
a value that never moved at all.

### §8 — Verify against the real target environment, not a look-alike

When a design depends on the specifics of a deployment environment &mdash; container runtime,
cgroup namespace, kernel behavior, CI platform &mdash; verify against the actual target
environment before trusting a similar-looking substitute. *Prevents* a design that looks
verified but only proves out on a stand-in: W-105's cgroup access worked in kind (a private
cgroup namespace) and in Docker Desktop's own Linux VM, but broke on the real CI runner (no
container boundary at all) &mdash; two different environments, two different results. *A
decision violates this when* "verified" cites an environment other than the one the code will
actually run in, without checking whether the substitution changes the thing being tested.

### §9 — When fixing a bug, verify the whole path, not just the piece you changed

A piece of the design reasoned to be "unaffected" by a bug is still only an assumption until
it is exercised end-to-end &mdash; sound reasoning about an untested piece is not the same as
verification. *Prevents* fixing one broken step while a second, earlier step in the same path
stays silently broken for the identical underlying reason: bug #55's own design claimed
`TargetLocator` was unaffected by the UID-mismatch problem (correct reasoning about
`/proc/PID/comm` not crossing namespaces), but `TargetLocator` used `VirtualMachine.list()`
under the hood, which reads each candidate's `hsperfdata` file &mdash; the same
cross-container-filesystem restriction that broke the Attach API, one step earlier in the
pipeline. Only found by deploying the "fixed" code end-to-end and seeing the pod still not
reach Ready. *A decision violates this when* a design says a piece "isn't affected" without a
citation of having actually run that piece under the real failure condition.

### §10 — A process's exit-code space must be deliberately partitioned

Any process whose exit code is programmatically asserted on (a test harness driver, a CLI tool)
must choose its recognized-outcome codes so none of them can collide with the runtime's own
default failure code (e.g. a bare uncaught exception exiting `1`), and must catch unexpected
failures explicitly to exit a distinct, reserved code rather than falling through to that
default. *Prevents* an unrelated crash being silently miscounted as a recognized, expected
outcome. *A decision violates this when* a "success" or a specific "expected failure" code is
the same value the language runtime already uses for an unhandled error, or when a process has no
top-level catch-all directing unexpected failures to their own code — exactly the gap that let
`ShrinkTrialDriver`'s original `EXIT_ABORTED = 1` collide with an uncaught JMX exception during
W-206's real-cluster verification, producing a false PASS in `verify-oomkill-safety.sh`.

## Out of scope

- **Formatting and mechanical style** (indentation, import order, line length) — owned by
  the formatter/linter, not this document.
- **Choice of GC or Kubernetes-client library** — §2 keeps these swappable rather than
  fixing a specific one here.
