package io.github.baokhang83.mnemo.warden.cache;

import java.beans.ConstructorProperties;

/**
 * A snapshot returned by {@link CacheHook#stats()}. A JavaBean-style class with a {@link
 * ConstructorProperties}-annotated constructor &mdash; not a record &mdash; because standard
 * MXBeans reconstruct compound return values via reflection over {@code getX()}/{@code isX()}
 * getters plus a matching {@code @ConstructorProperties} constructor (the same pattern {@code
 * java.lang.management.MemoryUsage} uses); a record's unprefixed accessors don't satisfy that
 * convention and would silently fail to round-trip through the standard MXBean machinery.
 */
public final class CacheStats {

  private final long entryCount;
  private final long evictableEntryCount;
  private final double hitRate;

  @ConstructorProperties({"entryCount", "evictableEntryCount", "hitRate"})
  public CacheStats(long entryCount, long evictableEntryCount, double hitRate) {
    this.entryCount = entryCount;
    this.evictableEntryCount = evictableEntryCount;
    this.hitRate = hitRate;
  }

  public long getEntryCount() {
    return entryCount;
  }

  public long getEvictableEntryCount() {
    return evictableEntryCount;
  }

  public double getHitRate() {
    return hitRate;
  }
}
