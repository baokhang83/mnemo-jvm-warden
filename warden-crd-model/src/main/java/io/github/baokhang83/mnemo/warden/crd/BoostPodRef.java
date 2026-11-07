package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;

/** Immutable pod name and UID supplied by the requester. */
public class BoostPodRef {

  @Required
  @Size(min = 1, max = 253)
  private String name;

  @Required
  @Size(min = 1, max = 253)
  private String uid;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getUid() {
    return uid;
  }

  public void setUid(String uid) {
    this.uid = uid;
  }
}
