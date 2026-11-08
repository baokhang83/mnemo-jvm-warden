package io.github.baokhang83.mnemo.warden.examples.boost;

import io.github.baokhang83.mnemo.warden.boost.client.BoostAcquisitionTimeoutException;
import io.github.baokhang83.mnemo.warden.boost.client.BoostLease;
import io.github.baokhang83.mnemo.warden.boost.client.BoostRejectedException;
import io.github.baokhang83.mnemo.warden.boost.client.WardenBoostClient;
import java.time.Duration;

/**
 * The B9 application example: a stand-in for a real app that needs a short-lived memory boost
 * for a known, bounded piece of work (an index rebuild), using exactly the try-with-resources
 * shape the dev plan specifies. The rebuild itself is synthetic &mdash; the point here is the
 * {@link BoostLease} wiring and its two distinct failure modes, not an actual index.
 *
 * <p>Requires a {@code warden-boost-policy-ref} with a matching {@code boosts.index-rebuild}
 * entry already deployed (see {@code deploy/example-sidecar-boost.yaml}), and the shared token
 * volume mounted into this app's own container.
 *
 * <p>The acquisition timeout defaults to 2 minutes but can be overridden with {@code
 * WARDEN_BOOST_EXAMPLE_TIMEOUT_SECONDS} — used by {@code deploy/verify-boost-client.py} to
 * exercise the timeout path quickly against a request with no controller to grant it, rather
 * than waiting out the full default.
 */
public final class IndexRebuildExample {

  public static void main(String[] args) throws Exception {
    Duration timeout = Duration.ofSeconds(parseTimeoutSeconds(System.getenv("WARDEN_BOOST_EXAMPLE_TIMEOUT_SECONDS")));
    WardenBoostClient warden = WardenBoostClient.discover();

    System.out.println("acquiring boost 'index-rebuild'...");
    try (BoostLease lease = warden.acquireBoost("index-rebuild", timeout)) {
      System.out.println("granted: requestId=" + lease.requestId());
      rebuildIndex();
      System.out.println("rebuild complete");
    } catch (BoostRejectedException e) {
      // A definite failure: the controller decided this request will never be granted.
      System.out.println("boost rejected: phase=" + e.phase() + " message=" + e.getMessage());
      System.exit(1);
    } catch (BoostAcquisitionTimeoutException e) {
      // Uncertain, not failed: per the dev plan, never assume the operation could not have
      // started. A real app would persist e.requestId() and reconcile later (retry acquisition
      // with the same ID, or call warden.inspect(id)) rather than silently giving up on it.
      System.out.println("acquisition timed out (uncertain outcome): requestId=" + e.requestId()
          + " lastObservedPhase=" + e.lastObservedPhase());
      System.exit(2);
    }
    System.out.println("done");
  }

  private static void rebuildIndex() throws InterruptedException {
    Thread.sleep(Duration.ofSeconds(5).toMillis());
  }

  private static long parseTimeoutSeconds(String raw) {
    return (raw == null || raw.isBlank()) ? 120 : Long.parseLong(raw.trim());
  }

  private IndexRebuildExample() {}
}
