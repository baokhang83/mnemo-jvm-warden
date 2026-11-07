package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;

/** A request for a named boost; completion is monotonic. */
public class WardenBoostSpec {

  @Required
  @Size(min = 1, max = 253)
  @ValidationRule(value = "self == oldSelf", message = "request identity is immutable")
  private String policyRef;

  @Required
  @ValidationRule(value = "self == oldSelf", message = "request identity is immutable")
  private BoostPodRef podRef;

  @Required
  @Size(min = 1, max = 253)
  @ValidationRule(value = "self == oldSelf", message = "request identity is immutable")
  private String container;

  @Required
  @Size(min = 1, max = 253)
  @ValidationRule(value = "self == oldSelf", message = "request identity is immutable")
  private String boost;

  @Required
  @io.fabric8.generator.annotation.Default("false")
  @ValidationRule(value = "!oldSelf || self", message = "completionRequested cannot change from true to false")
  private Boolean completionRequested;

  public String getPolicyRef() {
    return policyRef;
  }

  public void setPolicyRef(String policyRef) {
    this.policyRef = policyRef;
  }

  public BoostPodRef getPodRef() {
    return podRef;
  }

  public void setPodRef(BoostPodRef podRef) {
    this.podRef = podRef;
  }

  public String getContainer() {
    return container;
  }

  public void setContainer(String container) {
    this.container = container;
  }

  public String getBoost() {
    return boost;
  }

  public void setBoost(String boost) {
    this.boost = boost;
  }

  public Boolean getCompletionRequested() {
    return completionRequested;
  }

  public void setCompletionRequested(Boolean completionRequested) {
    this.completionRequested = completionRequested;
  }
}
