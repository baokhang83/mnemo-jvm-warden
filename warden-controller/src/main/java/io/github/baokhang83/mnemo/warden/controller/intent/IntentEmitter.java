package io.github.baokhang83.mnemo.warden.controller.intent;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;

/**
 * Resolves a profile to bytes and PATCHes the target pod's own annotations with it (W-306) —
 * read back by {@code warden-agent}'s {@code PodIntentReader}. The exact same two annotation
 * keys are duplicated on both sides deliberately: the two modules don't share a dependency
 * (agent has none on {@code warden-crd-model}), so the annotation contract is the only thing
 * that has to stay in sync, documented on both ends.
 *
 * <p>Scoped to {@code targetRef.kind == "Pod"} only, direct by name — every existing example
 * targets a pod directly. Resolving a {@code Deployment}/{@code StatefulSet} to its live pod(s)
 * via label selectors is separate, undesigned work (tracked as a follow-up issue), not built
 * here (§1).
 */
public final class IntentEmitter {

  /** Must match {@code PodIntentReader.ANNOTATION_REQUEST_BYTES} in warden-agent exactly. */
  public static final String ANNOTATION_REQUEST_BYTES = "warden.mnemo.io/target-request-bytes";

  /** Must match {@code PodIntentReader.ANNOTATION_LIMIT_BYTES} in warden-agent exactly. */
  public static final String ANNOTATION_LIMIT_BYTES = "warden.mnemo.io/target-limit-bytes";

  private static final String SUPPORTED_KIND = "Pod";

  private IntentEmitter() {}

  /**
   * PATCHes {@code targetRef}'s own annotations with {@code profile}'s resolved request/limit
   * bytes, in {@code namespace} (the WardenPolicy's own namespace — no cross-namespace targeting
   * yet). A no-op if {@code targetRef} isn't a {@code Pod} or {@code profile} can't be resolved.
   */
  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef, ResourceProfile profile) {
    if (targetRef == null || profile == null || !SUPPORTED_KIND.equals(targetRef.getKind())) {
      return;
    }
    if (profile.getRequest() == null || profile.getLimit() == null) {
      return;
    }

    long requestBytes = ResourceQuantity.parseBytes(profile.getRequest());
    long limitBytes = ResourceQuantity.parseBytes(profile.getLimit());

    client
        .pods()
        .inNamespace(namespace)
        .withName(targetRef.getName())
        .edit(
            pod ->
                new PodBuilder(pod)
                    .editMetadata()
                    .addToAnnotations(ANNOTATION_REQUEST_BYTES, String.valueOf(requestBytes))
                    .addToAnnotations(ANNOTATION_LIMIT_BYTES, String.valueOf(limitBytes))
                    .endMetadata()
                    .build());
  }
}
