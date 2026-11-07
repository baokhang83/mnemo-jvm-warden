package io.github.baokhang83.mnemo.warden.crd;

import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/** A namespaced temporary-memory request. Only confirmed status may authorize work. */
@Group("warden.mnemo.io")
@Version("v1alpha1")
public class WardenBoost extends CustomResource<WardenBoostSpec, WardenBoostStatus> implements Namespaced {
  @Override
  @Required
  public WardenBoostSpec getSpec() {
    return super.getSpec();
  }
}
