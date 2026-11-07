package io.github.baokhang83.mnemo.warden.controller.boost;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.Test;

class ControllerTopologyTest {
  @Test void requiresOneReplicaAndRecreateDeployment() {
    var deployment = new DeploymentBuilder().withNewSpec().withReplicas(1)
        .withNewStrategy().withType("Recreate").endStrategy().endSpec()
        .withNewStatus().withReplicas(1).endStatus().build();
    assertTrue(ControllerTopology.singleDeployment(deployment));
    deployment.getSpec().setReplicas(2);
    assertFalse(ControllerTopology.singleDeployment(deployment));
    deployment.getSpec().setReplicas(1);
    deployment.getSpec().getStrategy().setType("RollingUpdate");
    assertFalse(ControllerTopology.singleDeployment(deployment));
    deployment.getSpec().getStrategy().setType("Recreate");
    deployment.getStatus().setReplicas(2);
    assertFalse(ControllerTopology.singleDeployment(deployment));
  }
}
