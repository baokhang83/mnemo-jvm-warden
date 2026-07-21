package io.github.baokhang83.mnemo.warden.crd;

/**
 * How far ahead of a window's edge W-304 fires the corresponding transition: shrink at {@code
 * window_start - shrink}, warm at {@code peak_start - warm}. Duration strings (e.g. {@code
 * "5m"}), parsed by the ticket that evaluates them, not here.
 */
public class LeadTime {

  private String shrink;
  private String warm;

  public String getShrink() {
    return shrink;
  }

  public void setShrink(String shrink) {
    this.shrink = shrink;
  }

  public String getWarm() {
    return warm;
  }

  public void setWarm(String warm) {
    this.warm = warm;
  }
}
