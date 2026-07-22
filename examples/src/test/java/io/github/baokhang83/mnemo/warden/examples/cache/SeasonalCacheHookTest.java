package io.github.baokhang83.mnemo.warden.examples.cache;

import static io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity.percent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.baokhang83.mnemo.cache.SeasonalCache;
import io.github.baokhang83.mnemo.cache.schedule.ScheduleSpec;
import io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity;
import io.github.baokhang83.mnemo.warden.cache.CacheStats;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SeasonalCacheHook} against a real {@link SeasonalCache} &mdash; not a fake
 * backend &mdash; proving the reference adapter's own logic (key tracking, flush, pre-warm,
 * stats) works end to end against the actual mnemo-cache library.
 */
class SeasonalCacheHookTest {

  private SeasonalCache<String, String> cache;

  @AfterEach
  void shutdown() {
    if (cache != null) {
      cache.shutdown();
    }
  }

  @Test
  void flushEvictableInvalidatesEveryTrackedKey() {
    SeasonalCacheHook hook = newHook(Map.of());
    hook.put("cold-1", "value-1");
    hook.put("cold-2", "value-2");
    assertNotNull(cache.getIfPresent("cold-1"));
    assertNotNull(cache.getIfPresent("cold-2"));

    hook.flushEvictable();

    assertNull(cache.getIfPresent("cold-1"));
    assertNull(cache.getIfPresent("cold-2"));
  }

  @Test
  void aSeparatelyHeldHotMapIsUntouchedByFlushOrPreWarm() {
    Map<String, String> sessionCache = new ConcurrentHashMap<>();
    sessionCache.put("user-42-session", "authenticated");

    SeasonalCacheHook hook = newHook(Map.of("warm-key", () -> "warm-value"));
    hook.put("cold-1", "value-1");

    hook.flushEvictable();
    hook.preWarm();

    assertEquals(Map.of("user-42-session", "authenticated"), sessionCache);
  }

  @Test
  void preWarmReloadsTheConfiguredWarmSet() {
    SeasonalCacheHook hook =
        newHook(
            Map.of(
                "top-query-1", () -> "warmed-1",
                "top-query-2", () -> "warmed-2"));

    hook.preWarm();

    assertEquals("warmed-1", cache.getIfPresent("top-query-1"));
    assertEquals("warmed-2", cache.getIfPresent("top-query-2"));
  }

  @Test
  void statsReflectCurrentSizeAndComputedHitRate() {
    SeasonalCacheHook hook = newHook(Map.of());
    hook.put("k1", "v1");
    hook.put("k2", "v2");
    hook.getIfPresent("k1"); // hit
    hook.getIfPresent("missing"); // miss

    CacheStats stats = hook.stats();

    assertEquals(2, stats.getEntryCount());
    assertEquals(2, stats.getEvictableEntryCount(), "the whole cache is evictable by design");
    assertEquals(0.5, stats.getHitRate());
  }

  private SeasonalCacheHook newHook(Map<String, Supplier<String>> warmSet) {
    ScheduleSpec spec =
        SeasonalCapacity.named("test").max(100).min(10).zone("UTC").tick(Duration.ofMinutes(10))
            .startAt("00:00", percent(100))
            .build();
    cache = SeasonalCache.start(spec);
    return new SeasonalCacheHook(cache, warmSet);
  }
}
