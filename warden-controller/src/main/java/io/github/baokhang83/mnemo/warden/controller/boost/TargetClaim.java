package io.github.baokhang83.mnemo.warden.controller.boost;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import java.util.Objects;

/** A single durable claim per pod; pod UID and resourceVersion serialize competing writers. */
public final class TargetClaim {
  public static final String ANNOTATION = "warden.mnemo.io/boost-claim";
  private static final String PATH = "/metadata/annotations/warden.mnemo.io~1boost-claim";
  private TargetClaim() {}

  public static String owner(Pod pod) {
    return pod.getMetadata().getAnnotations() == null ? null : pod.getMetadata().getAnnotations().get(ANNOTATION);
  }

  /** Returns false for another owner. A conflicting write fails and requires a fresh read. */
  public static boolean acquire(KubernetesClient client, Pod pod, String requestUid) {
    Objects.requireNonNull(requestUid);
    String owner = owner(pod);
    if (owner != null) return owner.equals(requestUid);
    StringBuilder patch = preconditions(pod);
    if (pod.getMetadata().getAnnotations() == null || pod.getMetadata().getAnnotations().isEmpty())
      patch.append(",{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{}}");
    patch.append(",{\"op\":\"add\",\"path\":").append(JsonString.quote(PATH))
        .append(",\"value\":").append(JsonString.quote(requestUid)).append("}]");
    client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName())
        .patch(PatchContext.of(PatchType.JSON), patch.toString());
    return true;
  }

  /** Never release another request's claim, including after pod replacement. */
  public static void release(KubernetesClient client, Pod pod, String requestUid) {
    if (!requestUid.equals(owner(pod))) throw new IllegalStateException("claim ownership changed");
    StringBuilder patch = preconditions(pod).append(",{\"op\":\"test\",\"path\":")
        .append(JsonString.quote(PATH)).append(",\"value\":").append(JsonString.quote(requestUid))
        .append("},{\"op\":\"remove\",\"path\":").append(JsonString.quote(PATH)).append("}]");
    client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName())
        .patch(PatchContext.of(PatchType.JSON), patch.toString());
  }

  /** Clear a completed request's protocol annotations and claim in one fenced pod patch. */
  public static void finish(KubernetesClient client, Pod pod, String requestUid, String expectedIntent) {
    if (!requestUid.equals(owner(pod))) throw new IllegalStateException("claim ownership changed");
    var annotations = pod.getMetadata().getAnnotations();
    String actual = annotations.get(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION);
    if (actual != null && !actual.equals(expectedIntent)) throw new IllegalStateException("intent changed");
    StringBuilder patch = preconditions(pod).append(",{\"op\":\"test\",\"path\":")
        .append(JsonString.quote(PATH)).append(",\"value\":").append(JsonString.quote(requestUid)).append("}");
    if (actual != null) {
      String path = "/metadata/annotations/warden.mnemo.io~1resize-intent";
      patch.append(",{\"op\":\"test\",\"path\":").append(JsonString.quote(path))
          .append(",\"value\":").append(JsonString.quote(actual)).append("}")
          .append(",{\"op\":\"remove\",\"path\":").append(JsonString.quote(path)).append("}");
    }
    if (annotations.containsKey(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ACK_ANNOTATION))
      patch.append(",{\"op\":\"remove\",\"path\":\"/metadata/annotations/warden.mnemo.io~1resize-ack\"}");
    patch.append(",{\"op\":\"remove\",\"path\":").append(JsonString.quote(PATH)).append("}]");
    client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName())
        .patch(PatchContext.of(PatchType.JSON), patch.toString());
  }

  private static StringBuilder preconditions(Pod pod) {
    var meta = Objects.requireNonNull(pod.getMetadata());
    return new StringBuilder("[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":")
        .append(JsonString.quote(Objects.requireNonNull(meta.getUid())))
        .append("},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":")
        .append(JsonString.quote(Objects.requireNonNull(meta.getResourceVersion()))).append("}");
  }
}
