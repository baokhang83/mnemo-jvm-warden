package io.github.baokhang83.mnemo.warden.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * W-206 test fixture, not a production entry point: a real target JVM that retains a
 * controllable amount of live heap, so {@code deploy/verify-oomkill-safety.sh} can deterministically
 * pose two scenarios &mdash; adversarial load that must survive as a correctly aborted shrink, and
 * a genuinely idle heap that must survive as a completed one. A live-held allocation is used
 * instead of driving real HTTP traffic against it: traffic-driven garbage would mostly be
 * collected by {@code ShrinkSequence}'s own deep GC step regardless of "load," so it can't
 * reliably force the RSS gate either way &mdash; a fixed live set can.
 *
 * <p>Runs under the same JMX flags and {@code -XX:+UseG1GC} as {@code example-sidecar.yaml}'s
 * {@code app} container (see {@code TargetAttacher}'s javadoc for why those flags are load-bearing),
 * launched from the same image as a different main class ({@code java -cp warden-agent.jar
 * ...harness.LoadTarget}), so the safety check needs no separate image build.
 */
public final class LoadTarget {

  /** How many megabytes of live heap to retain; 0 (default) retains none. */
  public static final String ENV_RETAIN_MB = "WARDEN_LOAD_TARGET_RETAIN_MB";

  // Deliberately well under 1 MiB, not a round 1 MiB chunk: G1's default region size can be as
  // small as 1 MiB, and any object over half a region becomes a "humongous" allocation spanning
  // whole extra regions on its own — a 1 MiB byte[] (slightly over 1 MiB once its header is
  // added) hit exactly that on a 250m heap, needing roughly double the retained size in heap
  // space and OOMing well before reaching the intended live-set size. 256 KiB stays comfortably
  // under half of any region size G1 would pick at these heap sizes.
  private static final int CHUNK_BYTES = 256 * 1024;
  private static final int CHUNKS_PER_MB = (1024 * 1024) / CHUNK_BYTES;

  private LoadTarget() {}

  public static void main(String[] args) throws InterruptedException {
    int retainMb = parseRetainMb(System.getenv(ENV_RETAIN_MB));
    List<byte[]> retained = retain(retainMb);
    System.out.println("load-target ready, retainedMb=" + retainMb + " chunks=" + retained.size());

    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("load-target shutting down"), "load-target-shutdown"));
    new CountDownLatch(1).await();
  }

  static int parseRetainMb(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    return Integer.parseInt(raw.trim());
  }

  /**
   * Allocates {@code retainMb} megabytes' worth of chunks filled with non-zero, non-compressible
   * data (real pages the OS/collector can't dedupe or elide) and keeps a strong reference to
   * every one, so they stay live across {@code ShrinkSequence}'s deep GC step &mdash; that's the
   * point: this is simulated real application memory, not collectible garbage.
   *
   * <p>Package-private, not private: {@code LoadTargetTest} exercises this chunk-sizing
   * arithmetic directly &mdash; the same math a real cluster run caught a humongous-object bug
   * in &mdash; rather than forking a process just to prove it stays correct.
   */
  static List<byte[]> retain(int retainMb) {
    Random random = new Random(42); // fixed seed: deterministic fixture, not security-sensitive
    int chunkCount = retainMb * CHUNKS_PER_MB;
    List<byte[]> chunks = new ArrayList<>(chunkCount);
    for (int i = 0; i < chunkCount; i++) {
      byte[] chunk = new byte[CHUNK_BYTES];
      random.nextBytes(chunk);
      chunks.add(chunk);
    }
    return chunks;
  }
}
