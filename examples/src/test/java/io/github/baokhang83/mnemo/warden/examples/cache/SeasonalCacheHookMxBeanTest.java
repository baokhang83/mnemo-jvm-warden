package io.github.baokhang83.mnemo.warden.examples.cache;

import static io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity.percent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.baokhang83.mnemo.cache.SeasonalCache;
import io.github.baokhang83.mnemo.cache.schedule.ScheduleSpec;
import io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the real adapter survives the exact MXBean reflection path {@code CacheHookLookup} uses
 * in production ({@link JMX#newMXBeanProxy}) &mdash; {@link CacheHookLookupTest} in
 * {@code warden-agent} already proved that path works for <em>any</em> {@link CacheHook}; this
 * proves it for <em>this</em> one, without the cost of spawning a subprocess.
 */
class SeasonalCacheHookMxBeanTest {

  private static final MBeanServer MBS = ManagementFactory.getPlatformMBeanServer();

  private SeasonalCache<String, String> cache;
  private ObjectName name;

  @AfterEach
  void unregister() throws Exception {
    if (name != null) {
      MBS.unregisterMBean(name);
    }
    if (cache != null) {
      cache.shutdown();
    }
  }

  @Test
  void flushEvictableAndPreWarmRoundTripThroughARealMxBeanProxy() throws Exception {
    ScheduleSpec spec =
        SeasonalCapacity.named("queryCache").max(100).min(10).zone("UTC").tick(Duration.ofMinutes(10))
            .startAt("00:00", percent(100))
            .build();
    cache = SeasonalCache.start(spec);
    SeasonalCacheHook hook = new SeasonalCacheHook(cache, Map.of("warm-key", () -> "warm-value"));
    hook.put("cold-key", "cold-value");

    name = CacheHook.objectName("queryCache");
    MBS.registerMBean(hook, name);
    CacheHook proxy = JMX.newMXBeanProxy(MBS, name, CacheHook.class);

    assertEquals(1, proxy.stats().getEntryCount());

    proxy.flushEvictable();
    assertNull(cache.getIfPresent("cold-key"));

    proxy.preWarm();
    assertEquals("warm-value", cache.getIfPresent("warm-key"));
    assertEquals(1, proxy.stats().getEntryCount());
  }
}
