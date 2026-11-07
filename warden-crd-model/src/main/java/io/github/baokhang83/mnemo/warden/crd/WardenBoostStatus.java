package io.github.baokhang83.mnemo.warden.crd;


/** Controller-owned observed lifecycle; absent status never grants permission. */
public class WardenBoostStatus {

  private BoostPhase phase;

  private Long observedGeneration;

  private String policyUid;

  private Long policyGeneration;

  private String podUid;

  private String containerId;

  private String requestFloor;

  private String limitFloor;

  private String heapTarget;

  private String acquisitionDeadline;

  private String grantedAt;

  private String executionDeadline;

  private String completedAt;

  private String transitionId;

  @io.fabric8.generator.annotation.Size(max = 8192)
  private String preparedIntent;

  private String appliedRequest;

  private String appliedLimit;

  private String appliedHeapTarget;

  private java.util.List<io.fabric8.kubernetes.api.model.Condition> conditions;

  public BoostPhase getPhase() {
    return phase;
  }

  public void setPhase(BoostPhase phase) {
    this.phase = phase;
  }

  public Long getObservedGeneration() {
    return observedGeneration;
  }

  public void setObservedGeneration(Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }

  public String getPolicyUid() {
    return policyUid;
  }

  public void setPolicyUid(String policyUid) {
    this.policyUid = policyUid;
  }

  public Long getPolicyGeneration() {
    return policyGeneration;
  }

  public void setPolicyGeneration(Long policyGeneration) {
    this.policyGeneration = policyGeneration;
  }

  public String getPodUid() {
    return podUid;
  }

  public void setPodUid(String podUid) {
    this.podUid = podUid;
  }

  public String getContainerId() {
    return containerId;
  }

  public void setContainerId(String containerId) {
    this.containerId = containerId;
  }

  public String getRequestFloor() {
    return requestFloor;
  }

  public void setRequestFloor(String requestFloor) {
    this.requestFloor = requestFloor;
  }

  public String getLimitFloor() {
    return limitFloor;
  }

  public void setLimitFloor(String limitFloor) {
    this.limitFloor = limitFloor;
  }

  public String getHeapTarget() {
    return heapTarget;
  }

  public void setHeapTarget(String heapTarget) {
    this.heapTarget = heapTarget;
  }

  public String getAcquisitionDeadline() {
    return acquisitionDeadline;
  }

  public void setAcquisitionDeadline(String acquisitionDeadline) {
    this.acquisitionDeadline = acquisitionDeadline;
  }

  public String getGrantedAt() {
    return grantedAt;
  }

  public void setGrantedAt(String grantedAt) {
    this.grantedAt = grantedAt;
  }

  public String getExecutionDeadline() {
    return executionDeadline;
  }

  public void setExecutionDeadline(String executionDeadline) {
    this.executionDeadline = executionDeadline;
  }

  public String getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(String completedAt) {
    this.completedAt = completedAt;
  }

  /** Complete durable target paired atomically with transitionId. */
  public String getPreparedIntent() { return preparedIntent; }

  public void setPreparedIntent(String preparedIntent) { this.preparedIntent = preparedIntent; }

  public String getTransitionId() {
    return transitionId;
  }

  public void setTransitionId(String transitionId) {
    this.transitionId = transitionId;
  }

  public String getAppliedRequest() {
    return appliedRequest;
  }

  public void setAppliedRequest(String appliedRequest) {
    this.appliedRequest = appliedRequest;
  }

  public String getAppliedLimit() {
    return appliedLimit;
  }

  public void setAppliedLimit(String appliedLimit) {
    this.appliedLimit = appliedLimit;
  }

  public String getAppliedHeapTarget() {
    return appliedHeapTarget;
  }

  public void setAppliedHeapTarget(String appliedHeapTarget) {
    this.appliedHeapTarget = appliedHeapTarget;
  }

  public java.util.List<io.fabric8.kubernetes.api.model.Condition> getConditions() {
    return conditions;
  }

  public void setConditions(java.util.List<io.fabric8.kubernetes.api.model.Condition> conditions) {
    this.conditions = conditions;
  }
}
