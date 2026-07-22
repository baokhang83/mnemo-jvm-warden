# Session: Build the SeasonalCacheHook adapter, QueryCacheApp demo, and unit/MXBean/end-to-end tests

- **intent:** Build the SeasonalCacheHook adapter, QueryCacheApp demo, and unit/MXBean/end-to-end tests
- **started:** 2026-07-22

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`SeasonalCacheHook`** — wraps a real `SeasonalCache<String,String>` (mnemo-cache, Maven Central `0.1.0`). Tracks every inserted key locally in a `Set` because `SeasonalCache` has no `invalidateAll()` or enumeration; `flushEvictable()` invalidates each tracked key and clears the set, `preWarm()` re-loads a constructor-supplied `Map<String, Supplier<String>>` warm set via `cache.get(key, loader)`. Hit/miss counters live in the adapter too, since `SeasonalCacheState` carries no hit rate. · status: documented
- **`QueryCacheApp`** — the target-JVM demo: builds a flat (`100%`, never-shrinking) `ScheduleSpec` — the curve itself isn't the point, the `CacheHook` wiring is — registers only `queryCache`'s `SeasonalCacheHook` as an MXBean via `CacheHook.objectName("queryCache")`, and separately seeds a plain `ConcurrentHashMap` `sessionCache` that is never registered at all. Also satisfies W-005's original scope note (a sample target-JVM workload for Warden to attach to). · status: documented
- **Module dependency direction** — `examples` now depends on `warden-cache-api` + `mnemo-cache` + `caffeine` at compile scope, and on `warden-agent`'s main artifact at *test* scope only (for `ShrinkSequence`/`GrowSequence` in the end-to-end test) — a normal Maven dependency, no test-jar plugin needed, since those are `warden-agent`'s production classes. · status: documented
- **Test layering for this feature** — `SeasonalCacheHookTest` (adapter logic against a real `SeasonalCache`, no JMX), `SeasonalCacheHookMxBeanTest` (the same `JMX.newMXBeanProxy` reflection path `CacheHookLookup` uses in production, registered on the local platform MBeanServer, no subprocess), `SeasonalCacheHookEndToEndTest` (the real `ShrinkSequence`/`GrowSequence` against the real adapter, fake `HeapController`/`ResizePort` — mirrors `ShrinkSequenceTest`/`GrowSequenceTest`'s fakes since those are private nested classes in `warden-agent`'s test tree and aren't reusable across modules). No spawned-JVM integration test was added for this feature specifically because `CacheHookLookupTest` already covers that path generically. · status: documented

---

## Decision: flushEvictable() empties the whole cache, no per-key hot/cold split

- **where:** `examples/.../cache/SeasonalCacheHook.java`
- **why:** SeasonalCache exposes no per-key hotness signal (Caffeine's W-TinyLFU owns that internally) and no invalidateAll(); 'evictable' is decided at the granularity of which cache an app registers a hook for, matching CacheHook's own javadoc contract
- **alternative:** track per-key access counts inside the adapter to split hot/cold within one SeasonalCache — rejected: duplicates logic Caffeine already owns, and adds complexity a reference adapter's whole job is to avoid
- **design:** ../design.md#class-diagram
- **constitution:** §1
- **trust:** ✓ verified

## Decision: hot working set is a second, never-registered cache, not a flag

- **where:** `examples/.../cache/QueryCacheApp.java`
- **why:** registration is opt-in per W-501's SPI design, so a cache that's never registered is architecturally invisible to Warden, not just conventionally untouched — the strongest possible proof of the README's stateful-node claim
- **alternative:** one cache with per-entry pinning for 'do not evict' keys — rejected: real complexity for a demo whose value is being legible to an evaluator reading the source
- **design:** ../design.md#sequence-shrink-flushes-querycache-grow-pre-warms-it-sessioncache-is-never-touched
- **constitution:** §1
- **trust:** ✓ verified

## Decision: no changes to the mnemo-cache repository

- **where:** `examples/pom.xml`
- **why:** mnemo-cache is an independently versioned, already-published library (Maven Central 0.1.0) with zero knowledge of Warden; the adapter is built only against its existing public surface
- **alternative:** add a forced-evict primitive to mnemo-cache's public API — rejected: a cross-repo change to a published library for a distinction this reference app doesn't need
- **constitution:** §2
- **trust:** ✓ verified

## Decision: end-to-end test drives real ShrinkSequence/GrowSequence in-process, no spawned JVM

- **where:** `examples/pom.xml, examples/.../cache/SeasonalCacheHookEndToEndTest.java`
- **why:** CacheHookLookupTest (warden-agent) already proved the spawn/attach/JMX-discovery path works for any CacheHook; W-504 only needs to prove the real adapter's behavior when wired through real orchestration, which a normal test-scope dependency on warden-agent's main artifact gives for free
- **alternative:** spawn+attach a target JVM running QueryCacheApp, mirroring CacheHookLookupTest exactly — rejected: would re-prove already-proven JMX plumbing at the cost of subprocess flakiness and cross-module test-jar wiring
- **design:** ../design.md#sequence-shrink-flushes-querycache-grow-pre-warms-it-sessioncache-is-never-touched
- **constitution:** §1
- **trust:** ✓ verified
