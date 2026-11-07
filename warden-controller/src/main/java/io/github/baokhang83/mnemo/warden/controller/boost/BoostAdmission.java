package io.github.baokhang83.mnemo.warden.controller.boost;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator;
import io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates a new request and returns the values to persist before any resize is published. */
public final class BoostAdmission {
  /** Explicit, immutable-identity policy opt-in. Set by the workload operator. */
  public static final String POLICY_UID_LABEL = "warden.mnemo.io/policy-uid";
  private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ns|us|µs|ms|s|m|h)");
  private BoostAdmission() {}

  public record Snapshot(String policyUid, long policyGeneration, String podUid, String containerId,
      long requestFloor, long limitFloor, long heapTarget, Instant acquisitionDeadline,
      Duration maxDuration) {}

  /** Throws on missing or unverified identity; caller translates the failure to status. */
  public static Snapshot validate(KubernetesClient client, WardenBoost request, WardenPolicy policy,
      Pod pod, Instant now) {
    var meta = Objects.requireNonNull(request.getMetadata());
    var spec = Objects.requireNonNull(request.getSpec());
    var policyMeta = Objects.requireNonNull(policy.getMetadata());
    var policySpec = Objects.requireNonNull(policy.getSpec());
    var podMeta = Objects.requireNonNull(pod.getMetadata());
    if (!Objects.equals(meta.getNamespace(), policyMeta.getNamespace())
        || !Objects.equals(meta.getNamespace(), podMeta.getNamespace())
        || !Objects.equals(spec.getPolicyRef(), policyMeta.getName())
        || spec.getPodRef() == null || !Objects.equals(spec.getPodRef().getName(), podMeta.getName())
        || !Objects.equals(spec.getPodRef().getUid(), podMeta.getUid())
        || policyMeta.getUid() == null || podMeta.getUid() == null)
      throw new IllegalArgumentException("IdentityMismatch");
    if (podMeta.getLabels() == null || !policyMeta.getUid().equals(podMeta.getLabels().get(POLICY_UID_LABEL)))
      throw new IllegalArgumentException("TargetNotOptedIn");
    if (!isTarget(client, policy, pod)) throw new IllegalArgumentException("PodNotInPolicyTarget");
    if (podMeta.getAnnotations() != null
        && podMeta.getAnnotations().containsKey(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ANNOTATION))
      throw new IllegalArgumentException("ExistingCorrelatedIntent");
    if (Boolean.TRUE.equals(spec.getCompletionRequested())) throw new IllegalArgumentException("AlreadyCompleted");
    var definitions = Objects.requireNonNull(policySpec.getBoosts(), "NoBoostDefinitions");
    var definition = Objects.requireNonNull(definitions.get(spec.getBoost()), "UnknownBoost");
    var profiles = Objects.requireNonNull(policySpec.getProfiles(), "NoProfiles");
    var profile = Objects.requireNonNull(profiles.get(definition.getProfile()), "UnknownProfile");
    long requestBytes = ResourceQuantity.parseBytes(profile.getRequest());
    long limitBytes = ResourceQuantity.parseBytes(profile.getLimit());
    long heapBytes = ResourceQuantity.parseBytes(definition.getHeapTarget());
    if (requestBytes <= 0 || requestBytes > limitBytes || heapBytes <= 0 || heapBytes >= limitBytes)
      throw new IllegalArgumentException("InvalidFloor");
    Duration acquisition = duration(definition.getAcquisitionTimeout());
    Duration maximum = duration(definition.getMaxDuration());
    Instant deadline = Instant.parse(Objects.requireNonNull(meta.getCreationTimestamp())).plus(acquisition);
    if (!now.isBefore(deadline)) throw new IllegalArgumentException("AcquisitionTimeout");
    if (BlackoutEvaluator.isBlackedOut(policySpec, now)) throw new IllegalArgumentException("Blackout");
    if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null)
      throw new IllegalArgumentException("ContainerNotRunning");
    var statuses = pod.getStatus().getContainerStatuses();
    var container = statuses.stream().filter(c -> spec.getContainer().equals(c.getName())
        && c.getContainerID() != null && c.getState() != null && c.getState().getRunning() != null)
        .findFirst().orElseThrow(() -> new IllegalArgumentException("ContainerNotRunning"));
    if (container.getResources() == null || container.getResources().getRequests() == null
        || container.getResources().getLimits() == null
        || container.getResources().getRequests().get("memory") == null
        || container.getResources().getLimits().get("memory") == null)
      throw new IllegalArgumentException("AppliedAllocationUnavailable");
    return new Snapshot(policyMeta.getUid(), Objects.requireNonNull(policyMeta.getGeneration()), podMeta.getUid(),
        container.getContainerID(), requestBytes, limitBytes, heapBytes, deadline, maximum);
  }

  private static boolean isTarget(KubernetesClient client, WardenPolicy policy, Pod pod) {
    var target = policy.getSpec().getTargetRef();
    if (target == null || target.getKind() == null || target.getName() == null) return false;
    if ("Pod".equals(target.getKind())) return target.getName().equals(pod.getMetadata().getName());
    String namespace = policy.getMetadata().getNamespace();
    LabelSelector selector;
    if ("Deployment".equals(target.getKind())) {
      var workload = client.apps().deployments().inNamespace(namespace).withName(target.getName()).get();
      if (workload == null || workload.getSpec() == null) return false;
      selector = workload.getSpec().getSelector();
    } else if ("StatefulSet".equals(target.getKind())) {
      var workload = client.apps().statefulSets().inNamespace(namespace).withName(target.getName()).get();
      if (workload == null || workload.getSpec() == null) return false;
      selector = workload.getSpec().getSelector();
    } else return false;
    if (selector == null) return false;
    return client.pods().inNamespace(namespace).withLabelSelector(selector).list().getItems().stream()
        .anyMatch(p -> Objects.equals(p.getMetadata().getUid(), pod.getMetadata().getUid()));
  }

  /** Parse Kubernetes CEL's Go-style positive duration as an exact number of nanoseconds. */
  static Duration duration(String raw) {
    var matcher = PART.matcher(Objects.requireNonNull(raw));
    BigDecimal nanos = BigDecimal.ZERO;
    int end = 0;
    while (matcher.find()) {
      if (matcher.start() != end) throw new IllegalArgumentException("InvalidDuration");
      long factor = switch (matcher.group(2)) {
        case "ns" -> 1L; case "us", "µs" -> 1_000L; case "ms" -> 1_000_000L;
        case "s" -> 1_000_000_000L; case "m" -> 60_000_000_000L;
        case "h" -> 3_600_000_000_000L; default -> throw new IllegalArgumentException("InvalidDuration");
      };
      nanos = nanos.add(new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(factor)));
      end = matcher.end();
    }
    if (end != raw.length() || end == 0) throw new IllegalArgumentException("InvalidDuration");
    long value = nanos.longValueExact();
    if (value <= 0) throw new IllegalArgumentException("InvalidDuration");
    return Duration.ofNanos(value);
  }
}
