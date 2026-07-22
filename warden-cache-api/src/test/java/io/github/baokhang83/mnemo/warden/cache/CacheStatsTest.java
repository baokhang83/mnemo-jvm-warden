package io.github.baokhang83.mnemo.warden.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CacheStatsTest {

  @Test
  void gettersReturnExactlyWhatTheConstructorWasGiven() {
    CacheStats stats = new CacheStats(100, 40, 0.87);

    assertEquals(100, stats.getEntryCount());
    assertEquals(40, stats.getEvictableEntryCount());
    assertEquals(0.87, stats.getHitRate());
  }
}
