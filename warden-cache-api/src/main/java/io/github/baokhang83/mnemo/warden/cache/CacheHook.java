package io.github.baokhang83.mnemo.warden.cache;

import javax.management.MXBean;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * The SPI an app owner's own JVM implements and registers as an MXBean so {@code warden-agent}
 * can coordinate cache behavior around a resize (W-501): {@link #flushEvictable()} before a
 * shrink, {@link #preWarm()} after a grow (both wired up in later stories, not this one), and
 * {@link #stats()} for observability. Registration is entirely optional &mdash; an app that never
 * registers one is invisible to the agent, not an error (see {@code CacheHookLookup} in {@code
 * warden-agent}).
 *
 * <p>{@code @MXBean} (not the {@code CacheHookMBean} naming-convention style) so the
 * implementation class can be named anything the app owner likes &mdash; the class name carries
 * no protocol meaning.
 *
 * <p>One {@link ObjectName} can only ever name one MBean per {@code MBeanServer}, so an app with
 * more than one independently manageable cache registers each under its own {@code cacheName} via
 * {@link #objectName(String)} rather than a single fixed name.
 */
@MXBean
public interface CacheHook {

  String DOMAIN = "io.github.baokhang83.mnemo";
  String TYPE = "CacheHook";

  /** Matches every registered {@code CacheHook}'s {@link ObjectName}, regardless of cache name. */
  String OBJECT_NAME_PATTERN = DOMAIN + ":type=" + TYPE + ",name=*";

  /**
   * The {@link ObjectName} one cache instance registers itself under. {@code cacheName} must be
   * unique per JVM; it is quoted so an arbitrary app-chosen string can't produce a malformed
   * {@link ObjectName}.
   */
  static ObjectName objectName(String cacheName) throws MalformedObjectNameException {
    return new ObjectName(DOMAIN + ":type=" + TYPE + ",name=" + ObjectName.quote(cacheName));
  }

  /** Evicts (or otherwise sheds) whatever this cache considers safely evictable right now. */
  void flushEvictable();

  /** Re-populates whatever this cache considers worth having hot again. */
  void preWarm();

  /** A snapshot of this cache's current size/effectiveness. */
  CacheStats stats();
}
