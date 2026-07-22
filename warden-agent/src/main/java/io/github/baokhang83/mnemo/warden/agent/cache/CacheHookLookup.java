package io.github.baokhang83.mnemo.warden.agent.cache;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Discovers every {@link CacheHook} the target JVM has registered, over the same JMX connection
 * {@code SoftMax}/{@code G1PeriodicGc} already use to read platform MXBeans (W-501).
 *
 * <p>{@link #lookupAll(AttachedJvm)} queries {@link CacheHook#OBJECT_NAME_PATTERN} first and
 * builds one MXBean proxy per match, keyed by that registration's {@code name} key property. An
 * app that never registered a {@code CacheHook} gets back an empty map, not an exception &mdash;
 * "optional and safely absent" made concrete.
 */
public final class CacheHookLookup {

  private CacheHookLookup() {}

  public static Map<String, CacheHook> lookupAll(AttachedJvm target) throws IOException {
    MBeanServerConnection connection = target.mbeanConnection();
    Set<ObjectName> registered = connection.queryNames(pattern(), null);

    Map<String, CacheHook> hooks = new LinkedHashMap<>();
    for (ObjectName name : registered) {
      // CacheHook.objectName(...) always builds the "name" property via ObjectName.quote(...)
      // (see its javadoc), so getKeyProperty always hands back the quoted form here — verified
      // directly: even a plain alphanumeric cache name comes back wrapped in quotes.
      String cacheName = ObjectName.unquote(name.getKeyProperty("name"));
      hooks.put(cacheName, JMX.newMXBeanProxy(connection, name, CacheHook.class));
    }
    return hooks;
  }

  private static ObjectName pattern() {
    try {
      return new ObjectName(CacheHook.OBJECT_NAME_PATTERN);
    } catch (MalformedObjectNameException e) {
      throw new AssertionError("CacheHook.OBJECT_NAME_PATTERN is not a valid ObjectName pattern", e);
    }
  }
}
