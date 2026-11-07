package io.github.baokhang83.mnemo.warden.controller.boost;

import io.fabric8.kubernetes.client.KubernetesClient;

/** Fail closed for new grants when a non-leader-elected controller is scaled or rolling. */
final class ControllerTopology {
  private ControllerTopology() {}
  static boolean singleController(KubernetesClient client) {
    String namespace = System.getenv("WARDEN_CONTROLLER_NAMESPACE");
    String name = System.getenv("WARDEN_CONTROLLER_DEPLOYMENT");
    if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) return false;
    var deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
    return singleDeployment(deployment);
  }
  static boolean singleDeployment(io.fabric8.kubernetes.api.model.apps.Deployment deployment) {
    if (deployment == null || deployment.getSpec() == null || deployment.getStatus() == null) return false;
    var spec = deployment.getSpec();
    return Integer.valueOf(1).equals(spec.getReplicas()) && spec.getStrategy() != null
        && "Recreate".equals(spec.getStrategy().getType())
        && Integer.valueOf(1).equals(deployment.getStatus().getReplicas());
  }
}
