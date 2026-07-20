package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * Thrown when the target's cgroup hierarchy has no {@code memory.current} &mdash; i.e. it's
 * cgroup v1, not v2. Warden refuses to guess at a v1-equivalent path rather than silently
 * reading a number that doesn't mean what {@link RssReader} promises.
 */
public final class UnsupportedCgroupVersionException extends RuntimeException {

  public UnsupportedCgroupVersionException(long pid) {
    super("target (pid " + pid + ") is not on cgroup v2 (no memory.current); cgroup v1 is not supported");
  }
}
