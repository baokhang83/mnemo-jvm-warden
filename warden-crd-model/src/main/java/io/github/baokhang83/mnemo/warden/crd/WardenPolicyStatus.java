package io.github.baokhang83.mnemo.warden.crd;

/**
 * What the controller last observed, per W-302's own acceptance criteria ("status reflects
 * current profile"). Populated by the reconciler, not this slice.
 *
 * <p>{@code currentMetricValue} (W-401) is the latest {@code spec.guardrail.metric} PromQL
 * evaluation, purely observational in this slice &mdash; nothing acts on it yet (W-402, W-403).
 */
public class WardenPolicyStatus {

  private String currentProfile;
  private Double currentMetricValue;

  public String getCurrentProfile() {
    return currentProfile;
  }

  public void setCurrentProfile(String currentProfile) {
    this.currentProfile = currentProfile;
  }

  public Double getCurrentMetricValue() {
    return currentMetricValue;
  }

  public void setCurrentMetricValue(Double currentMetricValue) {
    this.currentMetricValue = currentMetricValue;
  }
}
