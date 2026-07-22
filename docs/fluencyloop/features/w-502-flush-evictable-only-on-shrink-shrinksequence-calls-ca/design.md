# Design: W-502 — Flush evictable-only on shrink: ShrinkSequence calls CacheHook.flushEvictable() on every registered app cache before the deep-GC/uncommit step, so shrink reclaims idle cache without ever touching the hot working set

started: 2026-07-22

Before `ShrinkSequence` runs its deep-GC/uncommit step, it now tells every app-registered
`CacheHook` to shed whatever it considers safely evictable — so a shrink reclaims idle cache,
never the hot working set the app owner is protecting.

## Class diagram

```mermaid
classDiagram
  class IntentWatcher {
    pollOnce()
  }
  class CacheHookLookup {
    +lookupAll(AttachedJvm) Map~String,CacheHook~
  }
  class ShrinkSequence {
    -Map~String,CacheHook~ cacheHooks
    +shrinkTo(requestBytes, limitBytes) ShrinkOutcome
  }
  class HeapController {
    <<interface>>
    +setSoftMax(bytes)
    +deepGcAndUncommit(timeout)
    +currentRss() long
  }
  class ResizePort {
    <<interface>>
    +resizeMemory(...)
  }
  class CacheHook {
    <<interface>>
    +flushEvictable()
    +preWarm()
    +stats() CacheStats
  }

  IntentWatcher --> CacheHookLookup : lookupAll(target)
  IntentWatcher --> ShrinkSequence : constructs + shrinkTo(...)
  ShrinkSequence --> HeapController
  ShrinkSequence --> ResizePort
  ShrinkSequence --> CacheHook : flushEvictable() × N, isolated per hook
  CacheHookLookup --> CacheHook : produces
```

## Sequence: one shrink attempt

```mermaid
sequenceDiagram
  participant IW as IntentWatcher
  participant SS as ShrinkSequence
  participant HC as HeapController
  participant CH as CacheHook[1..N]
  participant RP as ResizePort

  IW->>SS: shrinkTo(requestBytes, limitBytes)
  SS->>HC: setSoftMax(limitBytes)
  loop each registered CacheHook
    SS->>CH: flushEvictable()
    Note right of CH: throw is caught + logged by<br/>cache name — §12 isolation,<br/>never aborts the sequence
  end
  SS->>HC: deepGcAndUncommit(gcTimeout)
  SS->>HC: currentRss()
  alt rss < limitBytes (verified)
    SS->>RP: resizeMemory(request, limit, timeout)
    SS-->>IW: Completed(rss)
  else not verified
    SS-->>IW: AbortedVerificationFailed(rss, limit)
    Note right of SS: cgroup never touched — §5, unchanged
  end
```

## Decisions

- **Flush lives inside `ShrinkSequence`, not the caller.** Ordering (setSoftMax → flush →
  deep-GC/uncommit → verify → resize) is enforced in the sequence itself, not left to
  `IntentWatcher`/`ShrinkTrialDriver` discipline — constitution §5.
- **Per-hook failure isolation.** Each `flushEvictable()` call is individually caught and
  logged by cache name; one broken app cache can neither abort the shrink nor block its
  sibling hooks — constitution §12, applied to untrusted app-owner code.
