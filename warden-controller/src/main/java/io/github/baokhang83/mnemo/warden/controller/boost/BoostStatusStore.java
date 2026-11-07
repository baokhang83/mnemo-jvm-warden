package io.github.baokhang83.mnemo.warden.controller.boost;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.crd.WardenBoostStatus;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import java.util.Objects;

/** Compare-and-set lifecycle state; never overwrite a concurrent intent or completion. */
public final class BoostStatusStore {
  private BoostStatusStore() {}

  public static WardenBoost compareAndSet(KubernetesClient client, WardenBoost observed, WardenBoostStatus next) {
    var metadata = Objects.requireNonNull(observed.getMetadata());
    String patch = "[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":"
        + JsonString.quote(Objects.requireNonNull(metadata.getUid()))
        + "},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":"
        + JsonString.quote(Objects.requireNonNull(metadata.getResourceVersion()))
        + "},{\"op\":\"add\",\"path\":\"/status\",\"value\":"
        + client.getKubernetesSerialization().asJson(Objects.requireNonNull(next)) + "}]";
    WardenBoost saved = client.resources(WardenBoost.class).inNamespace(metadata.getNamespace()).withName(metadata.getName())
        .subresource("status").patch(PatchContext.of(PatchType.JSON), patch);
    try { BoostTelemetry.changed(client, observed, saved); }
    catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(BoostStatusStore.class).warn("Boost telemetry failed: {}", e.getMessage());
    }
    return saved;
  }
}
