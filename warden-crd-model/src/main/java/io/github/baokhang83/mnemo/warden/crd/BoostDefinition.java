package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;

/** Operator-approved temporary memory configuration. */
public class BoostDefinition {

  @Required
  @Size(min = 1, max = 253)
  private String profile;

  @Required
  @Size(min = 1, max = 64)
  @ValidationRule(value = "isQuantity(self)"
      + " && quantity(self).compareTo(quantity('0')) > 0"
      + " && quantity(self).isInteger()", message = "heapTarget must be a positive integral-byte quantity")
  private String heapTarget;

  @Required
  @Size(min = 1, max = 32)
  @ValidationRule(value = "duration(self) > duration('0s')", message = "acquisitionTimeout must be a positive duration")
  private String acquisitionTimeout;

  @Required
  @Size(min = 1, max = 32)
  @ValidationRule(value = "duration(self) > duration('0s')", message = "maxDuration must be a positive duration")
  private String maxDuration;

  public String getProfile() {
    return profile;
  }

  public void setProfile(String profile) {
    this.profile = profile;
  }

  public String getHeapTarget() {
    return heapTarget;
  }

  public void setHeapTarget(String heapTarget) {
    this.heapTarget = heapTarget;
  }

  public String getAcquisitionTimeout() {
    return acquisitionTimeout;
  }

  public void setAcquisitionTimeout(String acquisitionTimeout) {
    this.acquisitionTimeout = acquisitionTimeout;
  }

  public String getMaxDuration() {
    return maxDuration;
  }

  public void setMaxDuration(String maxDuration) {
    this.maxDuration = maxDuration;
  }
}
