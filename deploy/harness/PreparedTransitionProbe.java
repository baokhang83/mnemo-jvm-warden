import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.controller.intent.PreparedTransitionStore;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;

/** Tests the actual status-subresource resourceVersion fence on a disposable cluster. */
public class PreparedTransitionProbe {
  public static void main(String[] args) {
    String ns = args[0], name = args[1];
    try (var client = new KubernetesClientBuilder().build()) {
      var resource = client.resources(WardenBoost.class).inNamespace(ns).withName(name);
      resource.subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE),
          "{\"status\":{\"phase\":\"Preparing\",\"podUid\":\"pod-uid\",\"containerId\":\"cid\",\"policyUid\":\"policy-uid\",\"requestFloor\":\"100\",\"limitFloor\":\"400\",\"heapTarget\":\"200\",\"transitionId\":\"original\"}}");
      var original = resource.get();
      var first = new ResizeIntent("first", original.getMetadata().getUid(), "pod-uid", "app", "cid", 100, 400, 200);
      var second = new ResizeIntent("second", original.getMetadata().getUid(), "pod-uid", "app", "cid", 150, 500, 250);
      try {
        PreparedTransitionStore.prepare(client, original,
            new ResizeIntent("too-small", original.getMetadata().getUid(), "pod-uid", "app", "cid", 99, 400, 200));
        throw new AssertionError("below-floor target was accepted");
      } catch (IllegalArgumentException expected) { }
      try {
        PreparedTransitionStore.prepare(client, original,
            new ResizeIntent("wrong-instance", original.getMetadata().getUid(), "pod-uid", "app", "other", 100, 400, 200));
        throw new AssertionError("replaced instance was accepted");
      } catch (IllegalArgumentException expected) { }
      var saved = PreparedTransitionStore.prepare(client, original, first);
      if (!PreparedTransitionStore.current(saved).equals(first)) throw new AssertionError("first transition not durable");
      try {
        PreparedTransitionStore.prepare(client, original, second);
        throw new AssertionError("stale resource version was accepted");
      } catch (KubernetesClientException expected) { }
      if (!PreparedTransitionStore.current(resource.get()).equals(first)) throw new AssertionError("stale write won");
      try {
        PreparedTransitionStore.prepare(client, resource.get(),
            new ResizeIntent("first", original.getMetadata().getUid(), "pod-uid", "app", "cid", 150, 500, 250));
        throw new AssertionError("transition ID reused for a different target");
      } catch (IllegalArgumentException expected) { }
      var savedSecond = PreparedTransitionStore.prepare(client, resource.get(), second);
      if (!PreparedTransitionStore.current(savedSecond).equals(second)) throw new AssertionError("replacement not durable");
      try {
        PreparedTransitionStore.prepare(client, saved, first);
        throw new AssertionError("stale idempotent retry was accepted");
      } catch (KubernetesClientException expected) { }
      if (!PreparedTransitionStore.current(resource.get()).equals(second)) throw new AssertionError("stale retry won");
      System.out.println("PASS: status stores complete targets atomically; stale replacements and retries are rejected");
    }
  }
}
