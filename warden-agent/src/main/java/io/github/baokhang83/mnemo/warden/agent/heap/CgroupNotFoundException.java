package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * Thrown when the target's cgroup could not be located under the agent's host-mounted cgroup
 * view at all &mdash; distinct from {@link UnsupportedCgroupVersionException} (found, but it's
 * cgroup v1: nothing the operator can do). This one most likely means the agent's deployment is
 * missing the required {@code hostPath} mount of {@code /sys/fs/cgroup} (see {@code
 * RssReader}'s javadoc), which is fixable.
 */
public final class CgroupNotFoundException extends RuntimeException {

  public CgroupNotFoundException(long pid, String scopeName) {
    super("could not find a cgroup directory named \"" + scopeName + "\" (target pid " + pid
        + ") anywhere under the host-mounted cgroup view; is the hostPath mount configured?");
  }
}
