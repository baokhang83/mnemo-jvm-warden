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
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.controller.guardrail.AdmittedBoostFloor;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
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

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntentEmitter.class);

  private IntentEmitter() {}

  /**
   * Explains why a release cannot publish the lower target, or {@code null} when it can. Release
   * keeps the non-heap allowance (limit minus heap) of the boosted intent, so the scheduled
   * profile's limit must exceed it; otherwise the Boost would sit in {@code Releasing} forever.
   */
  public static String releaseBlockedDetail(String profileName, long profileLimitBytes, ResizeIntent held) {
    long allowance = held.limitBytes() - held.heapBytes();
    if (profileLimitBytes > allowance) return null;
    return "scheduled profile '" + profileName + "' limit " + profileLimitBytes + " bytes must exceed the "
        + allowance + " bytes of non-heap allowance kept from the boosted intent (limit " + held.limitBytes()
        + " minus heap " + held.heapBytes() + "); raise that profile's limit or narrow the boosted limit/heap gap";
  }

  /**
   * PATCHes {@code targetRef}'s live pod(s) with {@code profile}'s resolved request/limit bytes,
   * in {@code namespace} (the WardenPolicy's own namespace — no cross-namespace targeting yet).
   * A no-op if {@code targetRef}'s kind is unsupported, the target doesn't exist, or {@code
   * profile} can't be resolved.
   */
  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef, ResourceProfile profile) {
    emit(client, namespace, targetRef, profile, null, true, null);
  }

  /** Policy-aware writer; correlated intents require a persisted admission. */
  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef,
      ResourceProfile profile, WardenPolicy policy) {
    emit(client, namespace, targetRef, profile, policy, true, null);
  }

  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef,
      ResourceProfile profile, WardenPolicy policy, boolean shrinkAllowed) {
    emit(client, namespace, targetRef, profile, policy, shrinkAllowed, null);
  }

  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef,
      ResourceProfile profile, WardenPolicy policy, boolean shrinkAllowed, ResourceProfile scheduledFloor) {
    emit(client, namespace, targetRef, profile, policy, shrinkAllowed, scheduledFloor, false);
  }

  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef,
      ResourceProfile profile, WardenPolicy policy, boolean shrinkAllowed, ResourceProfile scheduledFloor,
      boolean correlatedScheduleUnavailable) {
    emit(client, namespace, targetRef, profile, policy, shrinkAllowed, scheduledFloor, correlatedScheduleUnavailable, false);
  }

  public static void emit(KubernetesClient client, String namespace, TargetRef targetRef,
      ResourceProfile profile, WardenPolicy policy, boolean shrinkAllowed, ResourceProfile scheduledFloor,
      boolean correlatedScheduleUnavailable, boolean legacyVetoed) {
    if (targetRef == null || profile == null || profile.getRequest() == null || profile.getLimit() == null) {
      return;
    }

    long requestBytes = ResourceQuantity.parseBytes(profile.getRequest());
    long limitBytes = ResourceQuantity.parseBytes(profile.getLimit());

    List<WardenBoost> boosts = List.of();
    if (policy != null) {
      try {
        boosts = client.resources(WardenBoost.class).inNamespace(namespace).list().getItems();
      } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
        // Helm upgrades do not install new CRDs. Preserve pre-Boost policy behavior only
        // when the API explicitly says the CRD is absent and this policy has no boosts.
        if (e.getCode() != 404 || (policy.getSpec().getBoosts() != null && !policy.getSpec().getBoosts().isEmpty())) throw e;
      }
    }
    for (String podName : resolvePodNames(client, namespace, targetRef)) {
      annotate(client, namespace, podName, requestBytes, limitBytes, profile.getHeapTarget(), policy, shrinkAllowed, scheduledFloor, correlatedScheduleUnavailable, boosts, legacyVetoed);
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

  private static void annotate(KubernetesClient client, String namespace, String podName, long requestBytes, long limitBytes, String profileHeap, WardenPolicy policy, boolean shrinkAllowed, ResourceProfile scheduledFloor, boolean correlatedScheduleUnavailable, List<WardenBoost> boosts, boolean legacyVetoed) {
    var resource = client.pods().inNamespace(namespace).withName(podName);
    Pod observed = resource.get();
    if (observed == null || observed.getMetadata().getResourceVersion() == null
        || observed.getMetadata().getUid() == null) return;
    String encoded = observed.getMetadata().getAnnotations() == null ? null
        : observed.getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION);
    String claim = io.github.baokhang83.mnemo.warden.controller.boost.TargetClaim.owner(observed);
    if (policy != null) {
      var admitted = boosts.stream().filter(b -> b.getSpec() != null && b.getSpec().getPodRef() != null
          && observed.getMetadata().getUid().equals(b.getSpec().getPodRef().getUid())
          && b.getStatus() != null && b.getStatus().getPhase() != null
          && b.getStatus().getPhase() != io.github.baokhang83.mnemo.warden.crd.BoostPhase.Pending
          && b.getStatus().getPhase() != io.github.baokhang83.mnemo.warden.crd.BoostPhase.Rejected
          && !((b.getStatus().getPhase() == io.github.baokhang83.mnemo.warden.crd.BoostPhase.Completed
              || b.getStatus().getPhase() == io.github.baokhang83.mnemo.warden.crd.BoostPhase.Orphaned)
              && b.getStatus().getConditions() != null && b.getStatus().getConditions().stream()
                  .anyMatch(c -> "Released".equals(c.getType()) && "True".equals(c.getStatus()))))
          .toList();
      if (admitted.size() > 1) return; // Conflicting claims: never select a winner by list order.
      if (!admitted.isEmpty()) {
        if (correlatedScheduleUnavailable) return;
        var boost = admitted.getFirst();
        if (claim != null && !claim.equals(boost.getMetadata().getUid())) return;
        if (encoded == null) {
          // A durable intent can survive a crash before the first pod annotation.
          if (!policy.getMetadata().getUid().equals(boost.getStatus().getPolicyUid())
              || !policy.getMetadata().getName().equals(boost.getSpec().getPolicyRef())) return;
          if (boost.getStatus().getPreparedIntent() == null) {
            // Only the policy writer can construct the first intent, so it includes the
            // current schedule/emergency target. Unknown prior heap requires quiet traffic.
            if (!shrinkAllowed) return;
            prepareInitial(client, observed, boost, policy, requestBytes, limitBytes, profileHeap, scheduledFloor);
            return;
          }
          var prepared = PreparedTransitionStore.current(boost);
          if (!shrinkAllowed && reducesObserved(observed, prepared, prepared.requestBytes(), prepared.limitBytes())) return;
          PreparedTransitionStore.prepareAndPublish(client, boost, prepared);
        } else {
          reconcileCorrelated(client, namespace, observed, requestBytes, limitBytes, profileHeap,
              policy, shrinkAllowed, scheduledFloor, boost);
        }
        return;
      }
    }
    // Unknown correlated ownership is not permission to overwrite it with a schedule.
    if (encoded != null || claim != null || legacyVetoed) return;
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
    if (observed.getMetadata().getAnnotations() == null || observed.getMetadata().getAnnotations().isEmpty()) {
      patch.append(",{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{}} ");
    }
    appendAnnotation(patch, ANNOTATION_REQUEST_BYTES, request);
    appendAnnotation(patch, ANNOTATION_LIMIT_BYTES, limit);
    if (correlated != null) appendAnnotation(patch, io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION, correlated);
    patch.append("]");
    resource.patch(io.fabric8.kubernetes.client.dsl.base.PatchContext.of(
        io.fabric8.kubernetes.client.dsl.base.PatchType.JSON), patch.toString());
  }

  private static void prepareInitial(KubernetesClient client, Pod pod, WardenBoost boost,
      WardenPolicy policy, long request, long limit, String profileHeap, ResourceProfile scheduledFloor) {
    var status = boost.getStatus();
    if (status.getPhase() != io.github.baokhang83.mnemo.warden.crd.BoostPhase.Preparing) return;
    var floor = AdmittedBoostFloor.read(boost, policy.getMetadata().getUid(), pod.getMetadata().getUid(),
        boost.getSpec().getContainer(), status.getContainerId()).orElseThrow();
    if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) return;
    var container = pod.getStatus().getContainerStatuses().stream().filter(c ->
        boost.getSpec().getContainer().equals(c.getName()) && status.getContainerId().equals(c.getContainerID())
        && c.getState() != null && c.getState().getRunning() != null).findFirst();
    if (container.isEmpty() || container.get().getResources() == null
        || container.get().getResources().getRequests() == null || container.get().getResources().getLimits() == null
        || container.get().getResources().getRequests().get("memory") == null
        || container.get().getResources().getLimits().get("memory") == null) return;
    request = Math.max(Math.max(request, floor.memory().request()),
        ResourceQuantity.parseBytes(container.get().getResources().getRequests().get("memory").toString()));
    limit = Math.max(Math.max(limit, floor.memory().limit()),
        ResourceQuantity.parseBytes(container.get().getResources().getLimits().get("memory").toString()));
    long heap = Math.max(floor.heapBytes(), profileHeap == null ? 0 : ResourceQuantity.parseBytes(profileHeap));
    if (scheduledFloor != null) {
      request = Math.max(request, ResourceQuantity.parseBytes(scheduledFloor.getRequest()));
      limit = Math.max(limit, ResourceQuantity.parseBytes(scheduledFloor.getLimit()));
      if (scheduledFloor.getHeapTarget() != null)
        heap = Math.max(heap, ResourceQuantity.parseBytes(scheduledFloor.getHeapTarget()));
    }
    if (heap >= limit) return;
    var intent = new ResizeIntent(java.util.UUID.randomUUID().toString(), boost.getMetadata().getUid(),
        pod.getMetadata().getUid(), boost.getSpec().getContainer(), status.getContainerId(), request, limit, heap);
    PreparedTransitionStore.prepareAndPublish(client, boost, intent);
  }

  /** Fail closed if the admitted request or its exact bound pod instance cannot be proven. */
  private static void reconcileCorrelated(KubernetesClient client, String namespace, Pod pod,
      long requestBytes, long limitBytes, String profileHeap, WardenPolicy policy, boolean shrinkAllowed,
      ResourceProfile scheduledFloor, WardenBoost boost) {
    if (scheduledFloor != null) {
      requestBytes = Math.max(requestBytes, ResourceQuantity.parseBytes(scheduledFloor.getRequest()));
      limitBytes = Math.max(limitBytes, ResourceQuantity.parseBytes(scheduledFloor.getLimit()));
      if (scheduledFloor.getHeapTarget() != null && (profileHeap == null ||
          ResourceQuantity.parseBytes(scheduledFloor.getHeapTarget()) > ResourceQuantity.parseBytes(profileHeap)))
        profileHeap = scheduledFloor.getHeapTarget();
    }
    var existing = ResizeIntent.decode(pod.getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
    if (policy.getMetadata().getUid() == null || !existing.podUid().equals(pod.getMetadata().getUid())
        || CorrelatedIntentEmitter.currentContainer(pod, existing).isEmpty()) return;
    if (boost.getMetadata() == null || !existing.requestUid().equals(boost.getMetadata().getUid())) return;
    if (boost.getSpec() == null || !policy.getMetadata().getName().equals(boost.getSpec().getPolicyRef())) return;
    var floor = AdmittedBoostFloor.read(boost, policy.getMetadata().getUid(), existing.podUid(),
        existing.containerName(), existing.containerId());
    boolean releasing = boost.getStatus().getPhase() == io.github.baokhang83.mnemo.warden.crd.BoostPhase.Releasing
        && boost.getStatus().getConditions() != null && boost.getStatus().getConditions().stream()
            .anyMatch(c -> "WorkFinished".equals(c.getType()) && "True".equals(c.getStatus()));
    if (floor.isEmpty() && !releasing) return;
    if (boost.getStatus().getPreparedIntent() != null) {
      var prepared = PreparedTransitionStore.current(boost);
      if (!prepared.equals(existing)) {
        // A previous quiet period is not evidence that a shrink is still safe now.
        if (!shrinkAllowed && (prepared.heapBytes() < existing.heapBytes()
            || reducesObserved(pod, prepared, prepared.requestBytes(), prepared.limitBytes()))) return;
        PreparedTransitionStore.prepareAndPublish(client, boost, prepared);
        return;
      }
    } else if (floor.isPresent() && !floor.get().transitionId().equals(existing.transitionId())) {
      return;
    }
    long heldRequest, heldLimit, heldHeap;
    if (releasing) {
      var container = CorrelatedIntentEmitter.currentContainer(pod, existing).orElseThrow();
      var resources = container.getResources();
      if (resources == null || resources.getRequests() == null || resources.getLimits() == null
          || resources.getRequests().get("memory") == null || resources.getLimits().get("memory") == null) return;
      long actualRequest = ResourceQuantity.parseBytes(resources.getRequests().get("memory").toString());
      long actualLimit = ResourceQuantity.parseBytes(resources.getLimits().get("memory").toString());
      if (!shrinkAllowed && (requestBytes < actualRequest || limitBytes < actualLimit)) return;
      heldRequest = requestBytes; heldLimit = limitBytes;
      // Retain at least the existing explicit non-heap allowance; never invent a larger heap.
      long allowance = existing.limitBytes() - existing.heapBytes();
      if (heldLimit <= allowance) {
        log.warn("Boost {} release blocked: {}", boost.getMetadata().getName(),
            releaseBlockedDetail("current", heldLimit, existing));
        return;
      }
      heldHeap = profileHeap == null ? Math.min(existing.heapBytes(), heldLimit - allowance)
          : ResourceQuantity.parseBytes(profileHeap);
      if (heldHeap <= 0 || heldHeap > heldLimit - allowance) return;
    } else {
      var container = CorrelatedIntentEmitter.currentContainer(pod, existing).orElseThrow();
      var resources = container.getResources();
      if (resources == null || resources.getRequests() == null || resources.getLimits() == null
          || resources.getRequests().get("memory") == null || resources.getLimits().get("memory") == null) return;
      heldRequest = Math.max(requestBytes, floor.get().memory().request());
      heldLimit = Math.max(limitBytes, floor.get().memory().limit());
      heldHeap = profileHeap == null ? Math.max(existing.heapBytes(), floor.get().heapBytes())
          : Math.max(ResourceQuantity.parseBytes(profileHeap), floor.get().heapBytes());
      if (heldHeap >= heldLimit) return;
      if (!shrinkAllowed && reducesObserved(pod, existing, heldRequest, heldLimit)) return;
    }
    if (!shrinkAllowed && heldHeap < existing.heapBytes()) return;
    if (heldRequest == existing.requestBytes() && heldLimit == existing.limitBytes()
        && heldHeap == existing.heapBytes()
        && !(releasing && existing.transitionId().equals(boost.getStatus().getReleaseBaselineTransitionId()))) return;
    String seed = existing.encode() + ":" + heldRequest + ":" + heldLimit + ":" + heldHeap;
    String transition = java.util.UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    var next = new ResizeIntent(transition, existing.requestUid(), existing.podUid(), existing.containerName(),
        existing.containerId(), heldRequest, heldLimit, heldHeap);
    PreparedTransitionStore.prepareAndPublish(client, boost, next);
  }

  private static boolean reducesObserved(Pod pod, ResizeIntent intent, long request, long limit) {
    var container = CorrelatedIntentEmitter.currentContainer(pod, intent);
    if (container.isEmpty() || container.get().getResources() == null
        || container.get().getResources().getRequests() == null || container.get().getResources().getLimits() == null
        || container.get().getResources().getRequests().get("memory") == null
        || container.get().getResources().getLimits().get("memory") == null) return true;
    return request < ResourceQuantity.parseBytes(container.get().getResources().getRequests().get("memory").toString())
        || limit < ResourceQuantity.parseBytes(container.get().getResources().getLimits().get("memory").toString());
  }

  private static void appendAnnotation(StringBuilder patch, String key, String value) {
    patch.append(",{\"op\":\"add\",\"path\":")
        .append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote("/metadata/annotations/" + key.replace("~", "~0").replace("/", "~1")))
        .append(",\"value\":").append(io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(value)).append("}");
  }

  /** Legacy scheduling may never overwrite a correlated transition. */
  static Pod applyScheduled(Pod pod, long requestBytes, long limitBytes) {
    var annotations = pod.getMetadata().getAnnotations();
    if (annotations != null && annotations.containsKey(ResizeIntent.ANNOTATION)) return pod;
    return new PodBuilder(pod).editMetadata()
        .addToAnnotations(ANNOTATION_REQUEST_BYTES, String.valueOf(requestBytes))
        .addToAnnotations(ANNOTATION_LIMIT_BYTES, String.valueOf(limitBytes))
        .endMetadata().build();
  }
}
