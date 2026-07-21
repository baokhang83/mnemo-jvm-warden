package io.github.baokhang83.mnemo.warden.crd;

/**
 * The live-metric thresholds M4 reads: {@code shrinkBelow} vetoes a scheduled shrink (W-402),
 * {@code emergencyGrowAbove} forces an off-schedule grow (W-403), both evaluated against {@code
 * metric} &mdash; a PromQL query (W-401 reads it on an interval; not evaluated here, §1).
 */
public class Guardrail {

  private String metric;
  private String shrinkBelow;
  private String emergencyGrowAbove;

  public String getMetric() {
    return metric;
  }

  public void setMetric(String metric) {
    this.metric = metric;
  }

  public String getShrinkBelow() {
    return shrinkBelow;
  }

  public void setShrinkBelow(String shrinkBelow) {
    this.shrinkBelow = shrinkBelow;
  }

  public String getEmergencyGrowAbove() {
    return emergencyGrowAbove;
  }

  public void setEmergencyGrowAbove(String emergencyGrowAbove) {
    this.emergencyGrowAbove = emergencyGrowAbove;
  }
}
