package io.github.baokhang83.mnemo.warden.examples.cache;

import static io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity.percent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.baokhang83.mnemo.cache.SeasonalCache;
import io.github.baokhang83.mnemo.cache.schedule.ScheduleSpec;
import io.github.baokhang83.mnemo.cache.schedule.SeasonalCapacity;
import io.github.baokhang83.mnemo.warden.agent.heap.Collector;
import io.github.baokhang83.mnemo.warden.agent.heap.GcCapabilities;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.sequence.GrowSequence;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkOutcome;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkSequence;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * W-504's actual acceptance criterion, proven in-process: drives the real {@link ShrinkSequence}
 * and {@link GrowSequence} (warden-agent) against the real {@link SeasonalCacheHook} wrapping a
 * real {@link SeasonalCache} (mnemo-cache) &mdash; the same orchestration classes production
 * wiring (IntentWatcher) uses, with a fake {@link HeapController}/{@link ResizePort} standing in
 * for the platform seams (§2), exactly as {@code ShrinkSequenceTest}/{@code GrowSequenceTest} do
 * in warden-agent. The stateful-node promise is the assertion that a separately-held hot map
 * survives both calls untouched, alongside the query cache actually emptying and refilling.
 */
class SeasonalCacheHookEndToEndTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private SeasonalCache<String, String> cache;

  @AfterEach
  void shutdown() {
    if (cache != null) {
      cache.shutdown();
    }
  }

  @Test
  void shrinkFlushesTheQueryCacheAndGrowPreWarmsItWhileTheHotMapNeverMoves() throws Exception {
    ScheduleSpec spec =
        SeasonalCapacity.named("queryCache").max(100).min(10).zone("UTC").tick(Duration.ofMinutes(10))
            .startAt("00:00", percent(100))
            .build();
    cache = SeasonalCache.start(spec);
    SeasonalCacheHook queryCacheHook = new SeasonalCacheHook(cache, Map.of("warm-key", () -> "warm-value"));
    queryCacheHook.put("cold-key", "cold-value");

    Map<String, String> sessionCache = new ConcurrentHashMap<>();
    sessionCache.put("user-42-session", "authenticated");
    Map<String, String> hotSnapshotBefore = Map.copyOf(sessionCache);

    Map<String, CacheHook> cacheHooks = Map.of("queryCache", queryCacheHook);
    FakeHeapController heap = new FakeHeapController();
    FakeResizePort resizeClient = new FakeResizePort();

    ShrinkSequence shrink =
        new ShrinkSequence(heap, resizeClient, cacheHooks, "query-pod", "app", TIMEOUT, TIMEOUT);
    ShrinkOutcome outcome = shrink.shrinkTo(100L * 1024 * 1024, 150L * 1024 * 1024);

    assertEquals(ShrinkOutcome.Completed.class, outcome.getClass());
    assertNull(cache.getIfPresent("cold-key"), "flushEvictable() must have emptied the query cache");
    assertEquals(hotSnapshotBefore, sessionCache, "shrink must never touch a cache it wasn't given");

    GrowSequence grow = new GrowSequence(heap, resizeClient, cacheHooks, "query-pod", "app", TIMEOUT);
    grow.growTo(150L * 1024 * 1024, 200L * 1024 * 1024);

    assertEquals("warm-value", cache.getIfPresent("warm-key"), "preWarm() must have re-loaded the warm set");
    assertEquals(hotSnapshotBefore, sessionCache, "grow must never touch a cache it wasn't given");
  }

  /** RSS always reports comfortably below whatever limit is requested, so the shrink always verifies. */
  private static final class FakeHeapController implements HeapController {
    private long lastSoftMax = Long.MAX_VALUE;

    @Override
    public long currentRss() {
      return lastSoftMax - 1;
    }

    @Override
    public void setSoftMax(long bytes) {
      lastSoftMax = bytes;
    }

    @Override
    public void deepGcAndUncommit(Duration timeout) {}

    @Override
    public GcCapabilities capabilities() {
      return new GcCapabilities(Collector.ZGC, true, true);
    }
  }

  private static final class FakeResizePort implements ResizePort {
    @Override
    public void resizeMemory(
        String podName, String containerName, long requestBytes, long limitBytes, Duration timeout) {}
  }
}
