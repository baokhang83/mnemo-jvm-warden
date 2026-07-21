package io.github.baokhang83.mnemo.warden.crd;

/**
 * One cron-defined window naming which {@link ResourceProfile} (by key in {@link
 * WardenPolicySpec#getProfiles()}) is active during it. W-303 evaluates these in the policy's
 * {@link WardenPolicySpec#getTimezone()}; cron syntax itself is not validated here (§1) &mdash;
 * that belongs to the ticket that actually parses it.
 */
public class ScheduleWindow {

  private String cron;
  private String profile;

  public String getCron() {
    return cron;
  }

  public void setCron(String cron) {
    this.cron = cron;
  }

  public String getProfile() {
    return profile;
  }

  public void setProfile(String profile) {
    this.profile = profile;
  }
}
