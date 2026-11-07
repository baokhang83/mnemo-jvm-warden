package io.github.baokhang83.mnemo.warden.agent.boost;

import java.io.IOException;
import java.util.Optional;

/**
 * The narrow seam {@link BoostApiServer} depends on, instead of the concrete {@link
 * BoostRequestClient} &mdash; constitution &sect;2: this request-limiting/bounded-wait logic is
 * unit tested against a fake, no real API server required, mirroring {@code ResizePort}'s
 * existing split for the resize sequences.
 */
public interface BoostRequestPort {

  /** The named boost, or empty if it doesn't exist. */
  Optional<BoostSnapshot> get(String name) throws IOException, InterruptedException;

  /** Creates the named boost if absent, otherwise returns the existing one unchanged. */
  BoostSnapshot getOrCreate(String name, String policyRef, String podName, String podUid, String container, String boost)
      throws IOException, InterruptedException;

  /** Requests completion, fenced against {@code resourceVersion}. */
  BoostSnapshot requestCompletion(String name, String resourceVersion) throws IOException, InterruptedException;
}
