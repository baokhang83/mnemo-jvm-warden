package io.github.baokhang83.mnemo.warden.crd;

import static org.junit.jupiter.api.Assertions.*;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Build-time contract checks; the deployment harness proves API-server enforcement. */
class BoostCrdGenerationTest {
  @Test
  void requestSchemaRequiresIdentityAndDoesNotGrantByDefault() throws Exception {
    var crd = Serialization.unmarshal(Files.readString(Path.of(
        "target/classes/META-INF/fabric8/wardenboosts.warden.mnemo.io-v1.yml")),
        CustomResourceDefinition.class);
    assertEquals("Namespaced", crd.getSpec().getScope());
    var version = crd.getSpec().getVersions().getFirst();
    assertNotNull(version.getSubresources().getStatus());
    var root = version.getSchema().getOpenAPIV3Schema();
    assertTrue(root.getRequired().contains("spec"));
    var spec = root.getProperties().get("spec");
    assertTrue(spec.getRequired().containsAll(
        java.util.List.of("policyRef", "podRef", "container", "boost", "completionRequested")));
    for (String field : java.util.List.of("policyRef", "podRef", "container", "boost")) {
      assertTrue(spec.getProperties().get(field).getXKubernetesValidations().stream()
          .anyMatch(rule -> "self == oldSelf".equals(rule.getRule())));
    }
    assertNull(root.getProperties().get("status").getProperties().get("phase").getDefault());
  }

  @Test
  void helmShipsBothGeneratedSchemasWithoutDrift() throws Exception {
    for (var pair : java.util.Map.of("wardenpolicy", "wardenpolicies",
                                     "wardenboost", "wardenboosts").entrySet()) {
      assertEquals(Files.readString(Path.of("target/classes/META-INF/fabric8/"
              + pair.getValue() + ".warden.mnemo.io-v1.yml")),
          Files.readString(Path.of("../charts/warden/crds/" + pair.getKey() + "-crd.yaml")));
    }
  }
}
