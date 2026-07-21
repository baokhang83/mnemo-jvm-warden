package io.github.baokhang83.mnemo.warden.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the placeholder profile selection directly, without a cluster or an {@code
 * Operator} — the reconcile/patch wiring itself is proven by {@code
 * deploy/verify-wardenpolicy-reconciler.sh} against a real kind cluster (constitution §8); this
 * is the fast, every-build check of the pure selection logic.
 */
class WardenPolicyReconcilerTest {

  @Test
  void picksTheAlphabeticallyFirstProfileKey() {
    WardenPolicy policy = policyWithProfiles("peak", "off-peak");

    assertEquals("off-peak", WardenPolicyReconciler.placeholderCurrentProfile(policy));
  }

  @Test
  void returnsNullWhenNoProfilesAreDeclared() {
    WardenPolicy policy = policyWithProfiles();

    assertNull(WardenPolicyReconciler.placeholderCurrentProfile(policy));
  }

  private static WardenPolicy policyWithProfiles(String... profileNames) {
    Map<String, ResourceProfile> profiles = new LinkedHashMap<>();
    for (String name : profileNames) {
      profiles.put(name, new ResourceProfile());
    }
    WardenPolicySpec spec = new WardenPolicySpec();
    spec.setProfiles(profiles);
    WardenPolicy policy = new WardenPolicy();
    policy.setSpec(spec);
    return policy;
  }
}
