package io.github.baokhang83.mnemo.warden.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;

class CacheHookTest {

  @Test
  void buildsAnObjectNameCarryingTheGivenCacheName() throws MalformedObjectNameException {
    ObjectName name = CacheHook.objectName("sessionCache");

    assertEquals("io.github.baokhang83.mnemo", name.getDomain());
    assertEquals("CacheHook", name.getKeyProperty("type"));
    // The "name" key property always comes back quoted (ObjectName.quote is unconditional in
    // CacheHook.objectName), so callers — CacheHookLookup included — must unquote it.
    assertEquals("sessionCache", ObjectName.unquote(name.getKeyProperty("name")));
  }

  @Test
  void distinctCacheNamesProduceDistinctObjectNames() throws MalformedObjectNameException {
    ObjectName sessionName = CacheHook.objectName("sessionCache");
    ObjectName queryName = CacheHook.objectName("queryCache");

    assertNotEquals(sessionName, queryName);
  }

  @Test
  void everyObjectNameMatchesTheDiscoveryPattern() throws MalformedObjectNameException {
    ObjectName pattern = new ObjectName(CacheHook.OBJECT_NAME_PATTERN);

    assertTrue(pattern.apply(CacheHook.objectName("sessionCache")));
    assertTrue(pattern.apply(CacheHook.objectName("queryCache")));
  }

  @Test
  void quotesAnAwkwardCacheNameRatherThanProducingAMalformedObjectName() throws MalformedObjectNameException {
    String awkward = "cache, with \"quotes\" and spaces";
    ObjectName name = CacheHook.objectName(awkward);

    assertEquals(awkward, ObjectName.unquote(name.getKeyProperty("name")));
  }
}
