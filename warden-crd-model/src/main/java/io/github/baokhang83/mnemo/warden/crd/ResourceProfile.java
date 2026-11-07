package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;

/**
 * A named memory configuration (e.g. {@code off-peak} / {@code peak}) the schedule and
 * lead-time machinery transitions between &mdash; plain Kubernetes quantity strings (e.g.
 * {@code "512Mi"}), the same representation {@code PodResizeClient} already parses.
 */
@ValidationRule(value = "!has(self.heapTarget) || (has(self.limit) && isQuantity(self.heapTarget) && "
    + "isQuantity(self.limit) && quantity(self.heapTarget).compareTo(quantity('0')) > 0 && "
    + "quantity(self.heapTarget).isInteger() && quantity(self.heapTarget).compareTo(quantity(self.limit)) < 0)",
    message = "heapTarget must be a positive integral-byte quantity below limit")
public class ResourceProfile {

  // Bounds CEL quantity-validation cost without changing ordinary quantity syntax.
  @Size(max = 64)
  private String request;
  @Size(max = 64)
  private String limit;
  @Size(min = 1, max = 64)
  private String heapTarget;

  public String getHeapTarget() {
    return heapTarget;
  }

  public void setHeapTarget(String heapTarget) {
    this.heapTarget = heapTarget;
  }

  public String getRequest() {
    return request;
  }

  public void setRequest(String request) {
    this.request = request;
  }

  public String getLimit() {
    return limit;
  }

  public void setLimit(String limit) {
    this.limit = limit;
  }
}
