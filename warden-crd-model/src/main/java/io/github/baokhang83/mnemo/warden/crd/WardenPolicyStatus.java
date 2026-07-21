package io.github.baokhang83.mnemo.warden.crd;

/**
 * What the controller last observed, per W-302's own acceptance criteria ("status reflects
 * current profile"). Populated by the reconciler, not this slice.
 */
public class WardenPolicyStatus {

  private String currentProfile;

  public String getCurrentProfile() {
    return currentProfile;
  }

  public void setCurrentProfile(String currentProfile) {
    this.currentProfile = currentProfile;
  }
}
