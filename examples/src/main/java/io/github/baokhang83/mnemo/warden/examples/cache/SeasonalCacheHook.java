package io.github.baokhang83.mnemo.warden.examples.cache;

import io.github.baokhang83.mnemo.cache.SeasonalCache;
import io.github.baokhang83.mnemo.cache.SeasonalCacheState;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import io.github.baokhang83.mnemo.warden.cache.CacheStats;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A working {@link CacheHook} for the real <a href="https://github.com/baokhang83/mnemo-cache">
 * mnemo-cache</a> library (W-504) &mdash; not a fake, and no changes to mnemo-cache itself: it's
 * built only against {@link SeasonalCache}'s existing public surface.
 *
 * <p>mnemo-cache exposes no per-key hotness signal (Caffeine's W-TinyLFU owns that judgment
 * internally) and no {@code invalidateAll()}, so this adapter treats the whole cache it wraps as
 * "evictable" &mdash; matching {@link CacheHook#flushEvictable()}'s own contract: whatever
 * <em>this cache</em> considers safely evictable, not a per-key split within it. An app that
 * wants a true hot working set simply keeps that state in a cache it never registers a {@link
 * CacheHook} for &mdash; see {@code QueryCacheApp}'s {@code sessionCache}.
 *
 * <p>Every key ever inserted is tracked locally, since {@link SeasonalCache} has no way to
 * enumerate or clear its own contents; {@link #flushEvictable()} invalidates each tracked key in
 * turn. Hit/miss counts are tracked here too, purely for {@link #stats()} &mdash;
 * {@link SeasonalCacheState} doesn't carry a hit rate.
 */
public final class SeasonalCacheHook implements CacheHook {

  private final SeasonalCache<String, String> cache;
  private final Map<String, Supplier<String>> warmSet;
  private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();

  public SeasonalCacheHook(SeasonalCache<String, String> cache, Map<String, Supplier<String>> warmSet) {
    this.cache = cache;
    this.warmSet = Map.copyOf(warmSet);
  }

  /** Get-or-load, exactly as an app would call it day-to-day. */
  public String get(String key, Supplier<String> loader) {
    String value = cache.get(key, k -> loader.get());
    trackedKeys.add(key);
    return value;
  }

  public String getIfPresent(String key) {
    String value = cache.getIfPresent(key);
    if (value != null) {
      hits.increment();
    } else {
      misses.increment();
    }
    return value;
  }

  public void put(String key, String value) {
    cache.put(key, value);
    trackedKeys.add(key);
  }

  /** Invalidates every key this adapter has ever inserted &mdash; the whole cache is evictable. */
  @Override
  public void flushEvictable() {
    for (String key : trackedKeys) {
      cache.invalidate(key);
    }
    trackedKeys.clear();
  }

  /** Re-loads the fixed warm set an app owner configured this adapter with. */
  @Override
  public void preWarm() {
    warmSet.forEach(
        (key, loader) -> {
          cache.get(key, k -> loader.get());
          trackedKeys.add(key);
        });
  }

  @Override
  public CacheStats stats() {
    SeasonalCacheState state = cache.state();
    long total = hits.sum() + misses.sum();
    double hitRate = total == 0 ? 0.0 : (double) hits.sum() / total;
    return new CacheStats(state.size(), state.size(), hitRate);
  }
}
