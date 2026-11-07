package io.github.baokhang83.mnemo.warden.crd;

/** Lifecycle labels; Failed and Orphaned can still require cleanup. */
public enum BoostPhase {
  Pending, Preparing, Granted, Releasing, Completed, Rejected, Failed, Expired, Orphaned
}
