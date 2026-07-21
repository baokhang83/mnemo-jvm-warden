package io.github.baokhang83.mnemo.warden.controller.guardrail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.crd.Guardrail;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShrinkVetoEvaluatorTest {

  @Test
  void vetoesAShrinkWhenTheMetricIsAtOrAboveShrinkBelow() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertTrue(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", 50.0));
    assertTrue(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", 75.0));
  }

  @Test
  void allowsAShrinkWhenTheMetricIsBelowShrinkBelow() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", 12.0));
  }

  @Test
  void vetoesAShrinkWhenNoMetricReadingIsAvailable_failClosed() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertTrue(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", null));
  }

  @Test
  void neverVetoesAGrow_shrinkBelowDoesNotApply() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "off-peak", "peak", null));
    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "off-peak", "peak", 999.0));
  }

  @Test
  void neverVetoesWhenTheCandidateEqualsThePreviousProfile() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "peak", null));
  }

  @Test
  void doesNothingWhenNoGuardrailIsConfigured() {
    WardenPolicySpec spec = offPeakPeakSpec(null);
    spec.setGuardrail(null);

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", null));
  }

  @Test
  void doesNothingWhenShrinkBelowIsNotConfigured_metricOnlyIsObservational() {
    WardenPolicySpec spec = offPeakPeakSpec(null);

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, "peak", "off-peak", 999.0));
  }

  @Test
  void doesNothingWhenThereIsNoPreviousProfileToShrinkFrom() {
    WardenPolicySpec spec = offPeakPeakSpec("50");

    assertFalse(ShrinkVetoEvaluator.vetoesShrink(spec, null, "off-peak", null));
  }

  /** off-peak (256Mi, smaller) / peak (512Mi, larger); guardrail.shrinkBelow as given (or unset if null). */
  private static WardenPolicySpec offPeakPeakSpec(String shrinkBelow) {
    WardenPolicySpec spec = new WardenPolicySpec();

    Map<String, ResourceProfile> profiles = new LinkedHashMap<>();
    profiles.put("off-peak", profile("128Mi", "256Mi"));
    profiles.put("peak", profile("384Mi", "512Mi"));
    spec.setProfiles(profiles);

    Guardrail guardrail = new Guardrail();
    guardrail.setMetric("sum(rate(http_requests_total[5m]))");
    guardrail.setShrinkBelow(shrinkBelow);
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
