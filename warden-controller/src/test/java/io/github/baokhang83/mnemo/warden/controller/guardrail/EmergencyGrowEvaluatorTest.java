package io.github.baokhang83.mnemo.warden.controller.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.crd.Guardrail;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmergencyGrowEvaluatorTest {

  @Test
  void growsToTheLargestProfileWhenTheMetricExceedsTheThreshold() {
    WardenPolicySpec spec = offPeakPeakSpec("500");

    assertEquals(Optional.of("peak"), EmergencyGrowEvaluator.emergencyProfile(spec, 501.0));
  }

  @Test
  void doesNothingWhenTheMetricEqualsTheThreshold_strictlyGreaterOnly() {
    WardenPolicySpec spec = offPeakPeakSpec("500");

    assertTrue(EmergencyGrowEvaluator.emergencyProfile(spec, 500.0).isEmpty());
  }

  @Test
  void doesNothingWhenTheMetricIsBelowTheThreshold() {
    WardenPolicySpec spec = offPeakPeakSpec("500");

    assertTrue(EmergencyGrowEvaluator.emergencyProfile(spec, 12.0).isEmpty());
  }

  @Test
  void doesNothingWhenNoMetricReadingIsAvailable_failOpen() {
    WardenPolicySpec spec = offPeakPeakSpec("500");

    assertTrue(EmergencyGrowEvaluator.emergencyProfile(spec, null).isEmpty());
  }

  @Test
  void doesNothingWhenNoGuardrailIsConfigured() {
    WardenPolicySpec spec = offPeakPeakSpec(null);
    spec.setGuardrail(null);

    assertTrue(EmergencyGrowEvaluator.emergencyProfile(spec, 999.0).isEmpty());
  }

  @Test
  void doesNothingWhenEmergencyGrowAboveIsNotConfigured_metricOnlyIsObservational() {
    WardenPolicySpec spec = offPeakPeakSpec(null);

    assertTrue(EmergencyGrowEvaluator.emergencyProfile(spec, 999.0).isEmpty());
  }

  @Test
  void picksTheLargestOfMoreThanTwoProfiles() {
    WardenPolicySpec spec = offPeakPeakSpec("500");
    spec.getProfiles().put("burst", profile("768Mi", "1Gi"));

    assertEquals(Optional.of("burst"), EmergencyGrowEvaluator.emergencyProfile(spec, 999.0));
  }

  /** off-peak (256Mi, smaller) / peak (512Mi, larger); guardrail.emergencyGrowAbove as given (or unset if null). */
  private static WardenPolicySpec offPeakPeakSpec(String emergencyGrowAbove) {
    WardenPolicySpec spec = new WardenPolicySpec();

    Map<String, ResourceProfile> profiles = new LinkedHashMap<>();
    profiles.put("off-peak", profile("128Mi", "256Mi"));
    profiles.put("peak", profile("384Mi", "512Mi"));
    spec.setProfiles(profiles);

    Guardrail guardrail = new Guardrail();
    guardrail.setMetric("sum(rate(http_requests_total[5m]))");
    guardrail.setEmergencyGrowAbove(emergencyGrowAbove);
    spec.setGuardrail(guardrail);

    return spec;
  }

  private static ResourceProfile profile(String request, String limit) {
    ResourceProfile profile = new ResourceProfile();
    profile.setRequest(request);
    profile.setLimit(limit);
    return profile;
  }
}
