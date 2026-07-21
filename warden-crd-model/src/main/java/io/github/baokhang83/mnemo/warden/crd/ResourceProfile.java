package io.github.baokhang83.mnemo.warden.crd;

/**
 * A named memory configuration (e.g. {@code off-peak} / {@code peak}) the schedule and
 * lead-time machinery transitions between &mdash; plain Kubernetes quantity strings (e.g.
 * {@code "512Mi"}), the same representation {@code PodResizeClient} already parses.
 */
public class ResourceProfile {

  private String request;
  private String limit;

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
