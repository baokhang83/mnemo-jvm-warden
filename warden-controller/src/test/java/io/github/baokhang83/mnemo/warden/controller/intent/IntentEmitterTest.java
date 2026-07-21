package io.github.baokhang83.mnemo.warden.controller.intent;

import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code emit}'s guard clauses directly — each passes a {@code null} client, which
 * would NPE immediately if any of them fell through to a real Fabric8 call, so a clean return is
 * itself the assertion. Real {@code Pod}/{@code Deployment}/{@code StatefulSet} resolution
 * behavior is proven against a real cluster by {@code deploy/verify-wardenpolicy-intent.sh}
 * (constitution §8), not a mock client here.
 */
class IntentEmitterTest {

  @Test
  void doesNothingWhenTargetRefIsNull() {
    IntentEmitter.emit(null, "default", null, profile("128Mi", "256Mi"));
  }

  @Test
  void doesNothingForAnUnrecognizedTargetRefKind() {
    IntentEmitter.emit(null, "default", targetRef("CronJob", "my-cronjob"), profile("128Mi", "256Mi"));
  }

  @Test
  void doesNothingWhenProfileIsNull() {
    IntentEmitter.emit(null, "default", targetRef("Pod", "my-pod"), null);
  }

  @Test
  void doesNothingWhenProfileHasNoRequestOrLimit() {
    IntentEmitter.emit(null, "default", targetRef("Pod", "my-pod"), new ResourceProfile());
  }

  private static TargetRef targetRef(String kind, String name) {
    TargetRef ref = new TargetRef();
    ref.setKind(kind);
    ref.setName(name);
    return ref;
  }

  private static ResourceProfile profile(String request, String limit) {
    ResourceProfile profile = new ResourceProfile();
    profile.setRequest(request);
    profile.setLimit(limit);
    return profile;
  }
}
