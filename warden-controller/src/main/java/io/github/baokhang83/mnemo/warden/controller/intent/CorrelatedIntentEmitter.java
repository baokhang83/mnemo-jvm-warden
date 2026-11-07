package io.github.baokhang83.mnemo.warden.controller.intent;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity;
import io.github.baokhang83.mnemo.warden.protocol.ResizeAcknowledgement;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import java.util.Map;
import java.util.Optional;

/** Per-pod transport for the later Boost resolver; never independently grants permission. */
public final class CorrelatedIntentEmitter {
  private CorrelatedIntentEmitter() {}

  /** Caller supplies a durable transition ID; retries must not generate a new identity. */
  public static void emit(KubernetesClient client, String namespace, String podName, ResizeIntent intent) {
    var resource = client.pods().inNamespace(namespace).withName(podName);
    Pod pod = resource.get();
    if (currentContainer(pod, intent).isEmpty() || pod.getMetadata().getResourceVersion() == null)
      throw new IllegalArgumentException("correlated target identity changed");
    resource.patch(PatchContext.of(PatchType.JSON), patch(pod, intent));
  }

  /** Atomic UID and resourceVersion tests fence replacement and concurrent intent changes. */
  static String patch(Pod observed, ResizeIntent intent) {
    StringBuilder json = new StringBuilder("[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":")
        .append(JsonString.quote(intent.podUid()))
        .append("},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":")
        .append(JsonString.quote(observed.getMetadata().getResourceVersion())).append("}");
    if (observed.getMetadata().getAnnotations() == null || observed.getMetadata().getAnnotations().isEmpty())
      json.append(",{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{}}");
    add(json, IntentEmitter.ANNOTATION_REQUEST_BYTES, Long.toString(intent.requestBytes()));
    add(json, IntentEmitter.ANNOTATION_LIMIT_BYTES, Long.toString(intent.limitBytes()));
    add(json, ResizeIntent.ANNOTATION, intent.encode());
    return json.append("]").toString();
  }

  private static void add(StringBuilder json, String key, String value) {
    json.append(",{\"op\":\"add\",\"path\":")
        .append(JsonString.quote("/metadata/annotations/" + key.replace("~", "~0").replace("/", "~1")))
        .append(",\"value\":").append(JsonString.quote(value)).append("}");
  }

  /** Requires live allocation plus evidence for the current intent, never desired pod spec. */
  public static Optional<ResizeAcknowledgement> evidence(Pod pod, ResizeIntent intent) {
    Optional<ContainerStatus> container = currentContainer(pod, intent);
    if (container.isEmpty()) return Optional.empty();
    Map<String, String> annotations = pod.getMetadata().getAnnotations();
    if (annotations == null || !intent.encode().equals(annotations.get(ResizeIntent.ANNOTATION))
        || !Long.toString(intent.requestBytes()).equals(annotations.get(IntentEmitter.ANNOTATION_REQUEST_BYTES))
        || !Long.toString(intent.limitBytes()).equals(annotations.get(IntentEmitter.ANNOTATION_LIMIT_BYTES)))
      return Optional.empty();
    try {
      var resources = container.get().getResources();
      if (resources == null || resources.getRequests() == null || resources.getLimits() == null)
        return Optional.empty();
      var request = resources.getRequests().get("memory");
      var limit = resources.getLimits().get("memory");
      if (request == null || limit == null) return Optional.empty();
      ResizeAcknowledgement ack = ResizeAcknowledgement.decode(annotations.get(ResizeIntent.ACK_ANNOTATION));
      return ack.confirms(intent, pod.getMetadata().getUid(), container.get().getContainerID(),
          ResourceQuantity.parseBytes(request.toString()), ResourceQuantity.parseBytes(limit.toString()))
          ? Optional.of(ack) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  static Optional<ContainerStatus> currentContainer(Pod pod, ResizeIntent intent) {
    if (pod == null || pod.getMetadata() == null || !intent.podUid().equals(pod.getMetadata().getUid())
        || pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null)
      return Optional.empty();
    return pod.getStatus().getContainerStatuses().stream()
        .filter(c -> intent.containerName().equals(c.getName()) && intent.containerId().equals(c.getContainerID())
            && c.getState() != null && c.getState().getRunning() != null).findFirst();
  }
}
