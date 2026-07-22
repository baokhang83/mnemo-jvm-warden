package io.github.baokhang83.mnemo.warden.agent.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CacheHookLookup} against a real spawned target JVM (not a mock), with the
 * app-side {@link CacheHook} implementation registered exactly the way a real app owner would:
 * {@code ManagementFactory.getPlatformMBeanServer().registerMBean(hook, CacheHook.objectName(...))}
 * inside the target process, with {@code warden-cache-api} on its classpath. This is also what
 * proves {@code CacheStats}'s {@code @ConstructorProperties} shape actually round-trips through
 * the standard MXBean machinery, not just that it compiles.
 */
class CacheHookLookupTest {

  private static final String TARGET_CLASSPATH = System.getProperty("java.class.path");

  @Test
  void findsARegisteredCacheHookAndRoundTripsItsStats() throws Exception {
    try (SpawnedJvm target =
        SpawnedJvm.withSource(
            """
            import io.github.baokhang83.mnemo.warden.cache.CacheHook;
            import io.github.baokhang83.mnemo.warden.cache.CacheStats;
            import java.lang.management.ManagementFactory;

            public class %s {
              public static void main(String[] args) throws Exception {
                CacheHook hook = new CacheHook() {
                  public void flushEvictable() {}
                  public void preWarm() {}
                  public CacheStats stats() { return new CacheStats(100, 40, 0.87); }
                };
                ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(hook, CacheHook.objectName("sessionCache"));
                System.out.println("registered");
                Thread.sleep(120_000);
              }
            }
            """,
            "-cp",
            TARGET_CLASSPATH)) {
      target.awaitReady();
      target.awaitStdoutLine("registered", Duration.ofSeconds(10));

      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Map<String, CacheHook> hooks = CacheHookLookup.lookupAll(attached);

        assertEquals(1, hooks.size());
        CacheHook hook = hooks.get("sessionCache");
        assertEquals(100, hook.stats().getEntryCount());
        assertEquals(40, hook.stats().getEvictableEntryCount());
        assertEquals(0.87, hook.stats().getHitRate());
        assertDoesNotThrow(hook::flushEvictable);
        assertDoesNotThrow(hook::preWarm);
      }
    }
  }

  @Test
  void findsMultipleRegisteredCacheHooksKeyedByCacheName() throws Exception {
    try (SpawnedJvm target =
        SpawnedJvm.withSource(
            """
            import io.github.baokhang83.mnemo.warden.cache.CacheHook;
            import io.github.baokhang83.mnemo.warden.cache.CacheStats;
            import java.lang.management.ManagementFactory;

            public class %s {
              public static void main(String[] args) throws Exception {
                CacheHook session = new CacheHook() {
                  public void flushEvictable() {}
                  public void preWarm() {}
                  public CacheStats stats() { return new CacheStats(10, 1, 0.5); }
                };
                CacheHook query = new CacheHook() {
                  public void flushEvictable() {}
                  public void preWarm() {}
                  public CacheStats stats() { return new CacheStats(20, 2, 0.6); }
                };
                var mbs = ManagementFactory.getPlatformMBeanServer();
                mbs.registerMBean(session, CacheHook.objectName("sessionCache"));
                mbs.registerMBean(query, CacheHook.objectName("queryCache"));
                System.out.println("registered");
                Thread.sleep(120_000);
              }
            }
            """,
            "-cp",
            TARGET_CLASSPATH)) {
      target.awaitReady();
      target.awaitStdoutLine("registered", Duration.ofSeconds(10));

      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Map<String, CacheHook> hooks = CacheHookLookup.lookupAll(attached);

        assertEquals(2, hooks.size());
        assertTrue(hooks.containsKey("sessionCache"));
        assertTrue(hooks.containsKey("queryCache"));
      }
    }
  }

  @Test
  void returnsAnEmptyMapWhenNoCacheHookIsRegistered() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper()) {
      target.awaitReady();

      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Map<String, CacheHook> hooks = CacheHookLookup.lookupAll(attached);

        assertTrue(hooks.isEmpty());
      }
    }
  }
}
