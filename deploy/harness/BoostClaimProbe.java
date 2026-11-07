import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.baokhang83.mnemo.warden.controller.boost.TargetClaim;
import io.github.baokhang83.mnemo.warden.controller.boost.BoostStatusStore;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.crd.WardenBoostStatus;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;

/** Exercise claim serialization and legacy-writer fencing against a real API server. */
public class BoostClaimProbe {
  public static void main(String[] args) {
    try (var client = new KubernetesClientBuilder().build()) {
      var pods = client.pods().inNamespace(args[0]).withName("worker");
      var original = pods.get();
      if (!TargetClaim.acquire(client, original, "request-a")) throw new AssertionError("first claim failed");
      try {
        TargetClaim.acquire(client, original, "request-b");
        throw new AssertionError("stale competing claim succeeded");
      } catch (io.fabric8.kubernetes.client.KubernetesClientException expected) { }
      var claimed = pods.get();
      if (TargetClaim.acquire(client, claimed, "request-b")) throw new AssertionError("second owner accepted");
      if (!TargetClaim.acquire(client, claimed, "request-a")) throw new AssertionError("retry not idempotent");
      var profile = new ResourceProfile(); profile.setRequest("64Mi"); profile.setLimit("256Mi");
      var ref = new TargetRef(); ref.setKind("Pod"); ref.setName("worker");
      IntentEmitter.emit(client, args[0], ref, profile);
      var fenced = pods.get();
      if (fenced.getMetadata().getAnnotations().containsKey(IntentEmitter.ANNOTATION_LIMIT_BYTES))
        throw new AssertionError("legacy writer bypassed pending claim");
      try { TargetClaim.release(client, fenced, "request-b"); throw new AssertionError("wrong owner released claim"); }
      catch (IllegalStateException expected) { }
      TargetClaim.release(client, fenced, "request-a");
      IntentEmitter.emit(client, args[0], ref, profile);
      if (!"268435456".equals(pods.get().getMetadata().getAnnotations().get(IntentEmitter.ANNOTATION_LIMIT_BYTES)))
        throw new AssertionError("legacy writer did not resume after release");
      var boosts = client.resources(WardenBoost.class).inNamespace(args[0]).withName("request");
      var originalBoost = boosts.get();
      var preparing = new WardenBoostStatus(); preparing.setPhase(BoostPhase.Preparing);
      BoostStatusStore.compareAndSet(client, originalBoost, preparing);
      var stale = new WardenBoostStatus(); stale.setPhase(BoostPhase.Granted);
      try { BoostStatusStore.compareAndSet(client, originalBoost, stale); throw new AssertionError("stale status won"); }
      catch (io.fabric8.kubernetes.client.KubernetesClientException expected) { }
      if (boosts.get().getStatus().getPhase() != BoostPhase.Preparing) throw new AssertionError("status was overwritten");
      System.out.println("PASS: claim and lifecycle status CAS, ownership, and policy-writer fencing");
    }
  }
}
