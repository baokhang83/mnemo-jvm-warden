package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.List;
import java.util.Map;

/** Scheduled resource policy with optional operator-approved temporary boosts.
 * Cross-field Boost checks apply only when boosts are configured.
 */
@ValidationRule(
    value = "!has(self.boosts) || self.boosts.all(k, has(self.profiles) && self.boosts[k].profile in self.profiles)",
    message = "every boost must reference an existing profile")
@ValidationRule(
    value = "!has(self.boosts) || self.boosts.all(k, !has(self.profiles) || !(self.boosts[k].profile in self.profiles) || (has(self.profiles[self.boosts[k].profile].request)"
        + " && has(self.profiles[self.boosts[k].profile].limit)"
        + " && isQuantity(self.profiles[self.boosts[k].profile].request)"
        + " && isQuantity(self.profiles[self.boosts[k].profile].limit)"
        + " && quantity(self.profiles[self.boosts[k].profile].request).compareTo(quantity('0')) > 0"
        + " && quantity(self.profiles[self.boosts[k].profile].request).isInteger()"
        + " && quantity(self.profiles[self.boosts[k].profile].limit).isInteger()"
        + " && quantity(self.profiles[self.boosts[k].profile].request).compareTo(quantity(self.profiles[self.boosts[k].profile].limit)) <= 0"
        + " && isQuantity(self.boosts[k].heapTarget)"
        + " && quantity(self.boosts[k].heapTarget).compareTo(quantity(self.profiles[self.boosts[k].profile].limit)) < 0))",
    message = "boost profiles require positive integral-byte request <= limit and heapTarget < limit")
public class WardenPolicySpec {

  private TargetRef targetRef;

  @Required
  private String timezone;

  private Map<String, ResourceProfile> profiles;

  @Size(max = 32)
  private Map<String, BoostDefinition> boosts;

  private List<ScheduleWindow> schedule;
  private LeadTime leadTime;
  private List<BlackoutWindow> blackout;
  private Guardrail guardrail;

  public Map<String, BoostDefinition> getBoosts() {
    return boosts;
  }

  public void setBoosts(Map<String, BoostDefinition> boosts) {
    this.boosts = boosts;
  }

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
