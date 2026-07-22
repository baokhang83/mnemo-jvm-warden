# Session: Build the CacheHook SPI: warden-cache-api module + CacheHookLookup discovery

- **intent:** Build the CacheHook SPI: warden-cache-api module + CacheHookLookup discovery
- **started:** 2026-07-22

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`warden-cache-api` (new module)** — dependency-free, mirroring `warden-crd-model`. Holds `CacheHook` (the `@MXBean`-annotated SPI interface with `flushEvictable()`/`preWarm()`/`stats()`) and `CacheStats` (the value type `stats()` returns). App-owner code compiles against this module alone, never against `warden-agent`. · status: documented
- **`CacheHook.objectName(String cacheName)`** — builds the `ObjectName` one cache instance registers under (`io.github.baokhang83.mnemo:type=CacheHook,name=<quoted cacheName>`), via `ObjectName.quote(...)` so an arbitrary app-chosen name can't produce a malformed `ObjectName`. `OBJECT_NAME_PATTERN` (`name=*`) is the wildcard `CacheHookLookup` queries against — this is what makes multiple independently-registered caches per app JVM representable at all, since a single fixed `ObjectName` can only ever name one MBean per `MBeanServer`. · status: documented
- **`CacheStats`** — a JavaBean-style class with a `@ConstructorProperties`-annotated constructor, not a record — the same pattern `java.lang.management.MemoryUsage` uses. This isn't a style choice: standard MXBeans reconstruct compound return values via reflection over `getX()`/`isX()` getters plus that annotated constructor; a record's unprefixed accessors wouldn't satisfy that convention and would silently fail to round-trip. Verified for real, not just by compiling — see `CacheHookLookupTest`. · status: documented
- **`CacheHookLookup.lookupAll(AttachedJvm)`** (in `warden-agent`) — queries `CacheHook.OBJECT_NAME_PATTERN` over the target's existing `MBeanServerConnection` (`AttachedJvm.mbeanConnection()`, the same channel `SoftMax`/`G1PeriodicGc` already use), then builds one `JMX.newMXBeanProxy(...)` per match, keyed by that registration's `name` key property. No caches registered → an empty map, never an exception. Does **not** unquote implicitly on its own initiative — it has to, because `ObjectName.getKeyProperty("name")` hands back the value exactly as stored, quotes included, since `CacheHook.objectName(...)` always quotes (see the decision below, caught by `CacheHookTest`/fixed here). · status: documented
- **`SpawnedJvm.withSource(String sourceTemplate, String... jvmArgs)`** (test support, `warden-agent`) — a thin public passthrough to the previously-private `spawn(...)`, added because `sleeper()`/`garbageChurner()` only offer fixed program bodies; `CacheHookLookupTest` needed a target that registers its own MBean(s) before parking, with `warden-cache-api` on its `-cp` (built from the test JVM's own `java.class.path`, which already carries it as a reactor dependency). · status: documented
- **out of scope, deliberately** — `ShrinkSequence`/`GrowSequence` don't call `flushEvictable()`/`preWarm()` yet (W-502/W-503); there's still no reference `CacheHook` implementation (W-504). · status: follow-up


## Decision: CacheHookLookup unquotes getKeyProperty("name") rather than using it raw

- **where:** `warden-agent/.../cache/CacheHookLookup.java`
- **why:** CacheHook.objectName(cacheName) unconditionally wraps the name key property via ObjectName.quote(...) (needed so an arbitrary app-chosen cacheName can't produce a malformed ObjectName). getKeyProperty(...) hands the value back exactly as stored, quotes included -- confirmed empirically by a failing CacheHookTest assertion (expected sessionCache but was "sessionCache") before this fix. Left unfixed, every key in lookupAll's returned map would literally include quote characters.
- **alternative:** Only quote cacheName when it actually contains characters that would break an unquoted ObjectName -- rejected: it would make getKeyProperty's return shape depend on the specific name chosen, so every reader would need to detect and conditionally unquote; unconditional quote + unconditional unquote is the simpler, uniform rule.
- **design:** ../design.md#discovery-mapstring-cachehook-empty-when-nothings-registered
- **trust:** ✓ verified
