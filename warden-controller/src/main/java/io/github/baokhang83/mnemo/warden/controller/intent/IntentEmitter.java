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
    client
        .pods()
        .inNamespace(namespace)
        .withName(podName)
        .edit(
            (Pod pod) ->
                new PodBuilder(pod)
                    .editMetadata()
                    .addToAnnotations(ANNOTATION_REQUEST_BYTES, String.valueOf(requestBytes))
                    .addToAnnotations(ANNOTATION_LIMIT_BYTES, String.valueOf(limitBytes))
                    .endMetadata()
                    .build());
  }
}
