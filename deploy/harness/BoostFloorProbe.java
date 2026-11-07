import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.intent.PreparedTransitionStore;
import io.github.baokhang83.mnemo.warden.crd.*;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;

/** Real API-server check for the per-pod schedule/Boost annotation handoff. */
public class BoostFloorProbe {
  public static void main(String[] args) throws Exception {
    String namespace = args[0], name = args[1];
    try (var client = new KubernetesClientBuilder().build()) {
      var resource = client.pods().inNamespace(namespace).withName(name);
      var pod = resource.get();
      var status = pod.getStatus().getContainerStatuses().getFirst();
      var request = new WardenBoost();
      request.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("boost").withNamespace(namespace).build());
      var requestSpec = new WardenBoostSpec(); var podRef = new BoostPodRef();
      podRef.setName(name); podRef.setUid(pod.getMetadata().getUid());
      requestSpec.setPodRef(podRef); requestSpec.setPolicyRef("policy"); requestSpec.setContainer("app");
      requestSpec.setBoost("maintenance"); request.setSpec(requestSpec);
      client.resources(WardenBoost.class).inNamespace(namespace).resource(request).create();
      var boosts = client.resources(WardenBoost.class).inNamespace(namespace).withName("boost");
      String statusJson = "{\"status\":{\"phase\":\"Preparing\",\"policyUid\":\"policy-uid\","
          + "\"podUid\":" + io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(pod.getMetadata().getUid())
          + ",\"containerId\":" + io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(status.getContainerID())
          + ",\"requestFloor\":\"64Mi\",\"limitFloor\":\"256Mi\",\"heapTarget\":\"32Mi\",\"transitionId\":\"old\"}}";
      boosts.subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE), statusJson);
      var boost = boosts.get();
      var intent = new ResizeIntent("initial", boost.getMetadata().getUid(), pod.getMetadata().getUid(), "app",
          status.getContainerID(), 67108864L, 268435456L, 33554432L);
      PreparedTransitionStore.prepareAndPublish(client, boost, intent);
      if (!PreparedTransitionStore.current(boosts.get()).equals(intent))
        throw new AssertionError("status must contain the transition before pod publication");
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(intent))
        throw new AssertionError("pod did not receive persisted transition");
      var stale = boosts.get();
      var newer = new ResizeIntent("newer", intent.requestUid(), intent.podUid(), "app", intent.containerId(),
          intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
      PreparedTransitionStore.prepareAndPublish(client, stale, newer);
      try {
        PreparedTransitionStore.prepareAndPublish(client, stale, intent);
        throw new AssertionError("stale publisher was accepted");
      } catch (io.fabric8.kubernetes.client.KubernetesClientException expected) { }
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(newer))
        throw new AssertionError("stale publisher overwrote newer transition");
      var recovery = new ResizeIntent("recovery", intent.requestUid(), intent.podUid(), "app", intent.containerId(),
          intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
      PreparedTransitionStore.prepare(client, boosts.get(), recovery); // simulate crash before pod patch
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(newer))
        throw new AssertionError("preparation unexpectedly modified pod");
      PreparedTransitionStore.prepareAndPublish(client, boosts.get(), recovery);
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(recovery))
        throw new AssertionError("reconcile failed to publish persisted transition");
      var ref = new TargetRef(); ref.setKind("Pod"); ref.setName(name);
      var peak = new ResourceProfile(); peak.setRequest("96Mi"); peak.setLimit("320Mi");
      IntentEmitter.emit(client, namespace, ref, peak);
      var grown = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (grown.requestBytes() != 100663296L || grown.limitBytes() != 335544320L
          || !grown.requestUid().equals(intent.requestUid()) || grown.transitionId().equals(intent.transitionId()))
        throw new AssertionError("correlated growth not emitted correctly");
      var low = new ResourceProfile(); low.setRequest("32Mi"); low.setLimit("128Mi");
      IntentEmitter.emit(client, namespace, ref, low);
      var held = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (!held.equals(grown)) throw new AssertionError("schedule reduced an active correlated floor");
      System.out.println("PASS: growth emitted with new transition; later shrink held at correlated floor");
    }
  }
}
