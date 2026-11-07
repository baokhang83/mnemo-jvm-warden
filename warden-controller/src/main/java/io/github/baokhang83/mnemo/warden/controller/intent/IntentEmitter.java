package io.github.baokhang83.mnemo.warden.controller.intent;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;
import java.util.List;

/**
 * Resolves a profile to bytes and PATCHes the target's live pod(s) with it (W-306, generalized
 * for #69) — read back by {@code warden-agent}'s {@code PodIntentReader}. The exact same two
 * annotation keys are duplicated on both sides deliberately: the two modules don't share a
 * dependency (agent has none on {@code warden-crd-model}), so the annotation contract is the
 * only thing that has to stay in sync, documented on both ends.
 *
 * <p>{@code targetRef.kind}: {@code Pod} is annotated directly by name; {@code Deployment}/{@code
 * StatefulSet} are resolved to their live pods via the workload's own {@code spec.selector} and
 * <em>every</em> matching pod is annotated identically — the schedule targets the whole
 * workload, not a subset of its replicas. Each pod's own agent already only reads its own pod's
 * annotations (W-306), so nothing on the agent side changes to support this.
 *
 * <p>A pod created by a later rollout won't have the annotation until the next reconcile —
 * {@link io.github.baokhang83.mnemo.warden.controller.WardenPolicyReconciler}'s 30s periodic
 * resync (not a per-policy dynamic secondary watch, which would need a selector known statically
 * at controller startup) catches it well within the schedule's own minute-level grain. See the
 * design doc for why the pod <em>template</em> isn't the transport instead: it would force a
 * full rolling restart just to deliver an annotation, the exact cost Warden exists to avoid.
 */
public final class IntentEmitter {

  /** Must match {@code PodIntentReader.ANNOTATION_REQUEST_BYTES} in warden-agent exactly. */
  public static final String ANNOTATION_REQUEST_BYTES = "warden.mnemo.io/target-request-bytes";

  /** Must match {@code PodIntentReader.ANNOTATION_LIMIT_BYTES} in warden-agent exactly. */
  public static final String ANNOTATION_LIMIT_BYTES = "warden.mnemo.io/target-limit-bytes";

  private IntentEmitter() {}

  /**
   * PATCHes {@code targetRef}'s live pod(s) with {@code profile}'s resolved request/limit bytes,
   * in {@code namespace} (the WardenPolicy's own namespace — no cross-namespace targeting yet).
   * A no-op if {@code targetRef}'s kind is unsupported, the target doesn't exist, or {@code
   * profile} can't be resolved.
   */
  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef, ResourceProfile profile) {
    if (targetRef == null || profile == null || profile.getRequest() == null || profile.getLimit() == null) {
      return;
    }

    long requestBytes = ResourceQuantity.parseBytes(profile.getRequest());
    long limitBytes = ResourceQuantity.parseBytes(profile.getLimit());

    for (String podName : resolvePodNames(client, namespace, targetRef)) {
      annotate(client, namespace, podName, requestBytes, limitBytes);
    }
  }

  private static List<String> resolvePodNames(KubernetesClient client, String namespace, TargetRef targetRef) {
    String kind = targetRef.getKind();
    if ("Pod".equals(kind)) {
      return List.of(targetRef.getName());
    }
    if ("Deployment".equals(kind)) {
      Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(targetRef.getName()).get();
      return deployment == null ? List.of() : podNamesForSelector(client, namespace, deployment.getSpec().getSelector());
    }
    if ("StatefulSet".equals(kind)) {
      StatefulSet statefulSet = client.apps().statefulSets().inNamespace(namespace).withName(targetRef.getName()).get();
      return statefulSet == null ? List.of() : podNamesForSelector(client, namespace, statefulSet.getSpec().getSelector());
    }
    return List.of();
  }

  private static List<String> podNamesForSelector(KubernetesClient client, String namespace, LabelSelector selector) {
    return client.pods().inNamespace(namespace).withLabelSelector(selector).list().getItems().stream()
        .map(pod -> pod.getMetadata().getName())
        .toList();
  }

  private static void annotate(KubernetesClient client, String namespace, String podName, long requestBytes, long limitBytes) {
    var resource = client.pods().inNamespace(namespace).withName(podName);
    Pod observed = resource.get();
    if (observed == null || observed.getMetadata().getResourceVersion() == null
        || observed.getMetadata().getUid() == null) return;
    Pod desired = applyScheduled(observed, requestBytes, limitBytes);
    if (desired == observed) return;
    var annotations = desired.getMetadata().getAnnotations();
    String request = annotations.get(ANNOTATION_REQUEST_BYTES);
    String limit = annotations.get(ANNOTATION_LIMIT_BYTES);
    String correlated = annotations.get(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION);
    StringBuilder patch = new StringBuilder("[{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":")
        .append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(observed.getMetadata().getResourceVersion()))
        .append("},{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":")
        .append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(observed.getMetadata().getUid())).append("}");
    if (observed.getMetadata().getAnnotations() == null) {
      patch.append(",{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{}} ");
    }
    appendAnnotation(patch, ANNOTATION_REQUEST_BYTES, request);
    appendAnnotation(patch, ANNOTATION_LIMIT_BYTES, limit);
    if (correlated != null) appendAnnotation(patch, io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION, correlated);
    patch.append("]");
    resource.patch(io.fabric8.kubernetes.client.dsl.base.PatchContext.of(
        io.fabric8.kubernetes.client.dsl.base.PatchType.JSON), patch.toString());
  }

  private static void appendAnnotation(StringBuilder patch, String key, String value) {
    patch.append(",{\"op\":\"add\",\"path\":")
        .append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote("/metadata/annotations/" + key.replace("~", "~0").replace("/", "~1")))
        .append(",\"value\":").append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(value)).append("}");
  }

  /** Preserve correlated floors while permitting increases, including emergency growth.
   * Shrink and release require the durable lifecycle in B5. */
  static Pod applyScheduled(Pod pod, long requestBytes, long limitBytes) {
    var annotations = pod.getMetadata().getAnnotations();
    String encoded = annotations == null ? null
        : annotations.get(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION);
    if (encoded == null) {
      return new PodBuilder(pod).editMetadata()
          .addToAnnotations(ANNOTATION_REQUEST_BYTES, String.valueOf(requestBytes))
          .addToAnnotations(ANNOTATION_LIMIT_BYTES, String.valueOf(limitBytes))
          .endMetadata().build();
    }
    try {
      var existing = io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.decode(encoded);
      if (!existing.podUid().equals(pod.getMetadata().getUid()) || pod.getStatus() == null
          || pod.getStatus().getContainerStatuses() == null) return pod;
      var container = pod.getStatus().getContainerStatuses().stream()
          .filter(c -> existing.containerName().equals(c.getName())
              && existing.containerId().equals(c.getContainerID())
              && c.getState() != null && c.getState().getRunning() != null)
          .findFirst().orElse(null);
      if (container == null || container.getResources() == null
          || container.getResources().getRequests() == null || container.getResources().getLimits() == null
          || container.getResources().getRequests().get("memory") == null
          || container.getResources().getLimits().get("memory") == null) return pod;
      long observedRequest = ResourceQuantity.parseBytes(container.getResources().getRequests().get("memory").toString());
      long observedLimit = ResourceQuantity.parseBytes(container.getResources().getLimits().get("memory").toString());
      var observed = new io.github.baokhang83.mnemo.warden.controller.guardrail.BoostTargetResolver.Memory(observedRequest, observedLimit);
      var scheduled = new io.github.baokhang83.mnemo.warden.controller.guardrail.BoostTargetResolver.Memory(requestBytes, limitBytes);
      var held = new io.github.baokhang83.mnemo.warden.controller.guardrail.BoostTargetResolver.Memory(existing.requestBytes(), existing.limitBytes()).max(observed);
      var decision = io.github.baokhang83.mnemo.warden.controller.guardrail.BoostTargetResolver.resolve(
          false, java.util.Optional.of(scheduled), java.util.Optional.empty(), java.util.Optional.of(held),
          java.util.Optional.of(observed), true, true);
      var target = decision.target().orElseThrow();
      if (target.request() == existing.requestBytes() && target.limit() == existing.limitBytes()) return pod;
      String seed = encoded + ":" + target.request() + ":" + target.limit();
      String transition = java.util.UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
      var next = new io.github.baokhang83.mnemo.warden.protocol.ResizeIntent(transition, existing.requestUid(),
          existing.podUid(), existing.containerName(), existing.containerId(), target.request(), target.limit(), existing.heapBytes());
      return new PodBuilder(pod).editMetadata()
          .addToAnnotations(ANNOTATION_REQUEST_BYTES, String.valueOf(target.request()))
          .addToAnnotations(ANNOTATION_LIMIT_BYTES, String.valueOf(target.limit()))
          .addToAnnotations(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION, next.encode())
          .endMetadata().build();
    } catch (IllegalArgumentException | ArithmeticException e) {
      return pod; // An unparseable or stale correlated target must not be overwritten.
    }
  }
}
