package io.github.baokhang83.mnemo.warden.crd;

/**
 * Which workload this policy governs &mdash; the same shape as HPA's {@code scaleTargetRef}, a
 * convention worth matching rather than inventing a new one.
 */
public class TargetRef {

  private String apiVersion;
  private String kind;
  private String name;

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
