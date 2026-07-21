package io.github.baokhang83.mnemo.warden.agent.heap;

import java.io.IOException;
import java.time.Duration;

/**
 * Collector-agnostic control over a target JVM's heap.
 *
 * <p>Implementations are per-collector drivers (W-103 / W-106 / W-107); {@link
 * AttachedHeapController} is the one real assembly, composing {@link SoftMax}, {@link DeepGc},
 * and {@link RssReader} over a single attached target. Callers &mdash; notably W-203's {@code
 * ShrinkSequence} &mdash; depend only on this port and on {@link #capabilities()}, never on the
 * concrete collector, so the safety logic stays GC-blind (constitution §2).
 */
public interface HeapController {

  /** Current resident set size (RSS) of the target process, in bytes. */
  long currentRss() throws IOException;

  /**
   * Requests a soft ceiling on the heap, in bytes. On collectors without a runtime soft max (e.g.
   * G1) this is a no-op &mdash; check {@link GcCapabilities#supportsSoftMax()} first.
   */
  void setSoftMax(long bytes);

  /**
   * Forces a deep GC and blocks until freed pages are returned to the OS or {@code timeout}
   * elapses &mdash; see {@link DeepGc#runAndAwaitUncommit} for why the timeout is the caller's
   * call: ZGC's default uncommit delay alone is 300 seconds, so no single value fits every
   * collector.
   */
  void deepGcAndUncommit(Duration timeout) throws IOException, InterruptedException;

  /** What this controller's collector actually supports. */
  GcCapabilities capabilities();
}
