package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import java.util.List;
import java.util.Map;

/**
 * The declarative policy body. Every field's shape is fixed by what a later M3/M4 ticket already
 * commits to doing with it (see the design doc), not invented fresh here &mdash; only {@link
 * #getTimezone()} is validated in this slice, the one criterion W-301 states explicitly.
 */
public class WardenPolicySpec {

  private TargetRef targetRef;

  @Required
  private String timezone;

  private Map<String, ResourceProfile> profiles;
  private List<ScheduleWindow> schedule;
  private LeadTime leadTime;
  private List<BlackoutWindow> blackout;
  private Guardrail guardrail;

  public TargetRef getTargetRef() {
    return targetRef;
  }

  public void setTargetRef(TargetRef targetRef) {
    this.targetRef = targetRef;
  }

  /** IANA zone id (e.g. {@code "Europe/Paris"}); required so W-303 always has a zone to evaluate
   *  the schedule in. */
  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  public Map<String, ResourceProfile> getProfiles() {
    return profiles;
  }

  public void setProfiles(Map<String, ResourceProfile> profiles) {
    this.profiles = profiles;
  }

  public List<ScheduleWindow> getSchedule() {
    return schedule;
  }

  public void setSchedule(List<ScheduleWindow> schedule) {
    this.schedule = schedule;
  }

  public LeadTime getLeadTime() {
    return leadTime;
  }

  public void setLeadTime(LeadTime leadTime) {
    this.leadTime = leadTime;
  }

  public List<BlackoutWindow> getBlackout() {
    return blackout;
  }

  public void setBlackout(List<BlackoutWindow> blackout) {
    this.blackout = blackout;
  }

  public Guardrail getGuardrail() {
    return guardrail;
  }

  public void setGuardrail(Guardrail guardrail) {
    this.guardrail = guardrail;
  }
}
