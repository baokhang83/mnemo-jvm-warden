import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.intent.CorrelatedIntentEmitter;
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
      var intent = new ResizeIntent("initial", "request", pod.getMetadata().getUid(), "app",
          status.getContainerID(), 67108864L, 268435456L, 33554432L);
      CorrelatedIntentEmitter.emit(client, namespace, name, intent);
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
