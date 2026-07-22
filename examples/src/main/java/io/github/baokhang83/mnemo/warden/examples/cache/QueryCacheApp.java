package io.github.baokhang83.mnemo.warden.examples.cache;

import static io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity.percent;

import io.github.baokhang83.mnemo.cache.SeasonalCache;
import io.github.baokhang83.mnemo.cache.schedule.ScheduleSpec;
import io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sample target JVM for the stateful-node story (W-005/W-504): a query cache backed by the
 * real mnemo-cache library, registered as the only {@link CacheHook} in the process, alongside a
 * session cache that is deliberately never registered &mdash; the hot working set an evaluator
 * can watch survive a real shrink/grow cycle untouched.
 *
 * <p>The curve itself isn't the point here (that's mnemo-cache's own concern); it's held flat so
 * the demo is entirely about the {@link CacheHook} wiring, not the seasonal schedule.
 */
public final class QueryCacheApp {

  public static void main(String[] args) throws Exception {
    ScheduleSpec spec =
        SeasonalCapacity.named("queryCache").max(1_000).min(50).zone("UTC").tick(Duration.ofSeconds(30))
            .startAt("00:00", percent(100))
            .build();
    SeasonalCache<String, String> seasonal = SeasonalCache.start(spec);

    Map<String, java.util.function.Supplier<String>> warmSet =
        Map.of(
            "top-query-1", () -> "warmed-1",
            "top-query-2", () -> "warmed-2");
    SeasonalCacheHook queryCacheHook = new SeasonalCacheHook(seasonal, warmSet);
    queryCacheHook.put("cold-query-1", "cold-result-1");
    queryCacheHook.put("cold-query-2", "cold-result-2");

    // The hot working set: never registered as a CacheHook, so it is invisible to Warden and
    // untouched by flushEvictable()/preWarm() no matter how many resize cycles run.
    Map<String, String> sessionCache = new ConcurrentHashMap<>();
    sessionCache.put("user-42-session", "authenticated");

    ManagementFactory.getPlatformMBeanServer()
        .registerMBean(queryCacheHook, CacheHook.objectName("queryCache"));
    System.out.println("ready");
    Thread.sleep(120_000);
  }

  private QueryCacheApp() {}
}
