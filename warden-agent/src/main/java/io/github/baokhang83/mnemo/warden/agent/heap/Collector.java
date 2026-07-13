package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * The garbage collectors Warden can reason about. Anything Warden cannot drive (Serial, Parallel,
 * Epsilon, or an unrecognised collector) is {@link #OTHER}.
 */
public enum Collector {
  ZGC,
  SHENANDOAH,
  G1,
  OTHER
}
