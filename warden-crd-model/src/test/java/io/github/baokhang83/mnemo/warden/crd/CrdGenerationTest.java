package io.github.baokhang83.mnemo.warden.crd;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks in the one thing W-301's acceptance criteria actually asks for: the generated CRD's
 * OpenAPI schema marks {@code timezone} required, so the API server itself rejects a policy
 * missing it &mdash; not just a Java-side check. This runs on every build, fast; {@code
 * deploy/verify-wardenpolicy-schema.sh} is the real-cluster proof that a generated schema with
 * this shape is actually enforced by a real API server (constitution §8).
 *
 * <p>Reads the file {@code crd-generator-maven-plugin} writes at {@code process-classes}
 * (verified to run before {@code test} in Maven's default lifecycle) rather than re-deriving the
 * schema from annotations directly &mdash; this is deliberately an end-to-end check of what the
 * build actually produces, not a unit test of the generator's own internals.
 */
class CrdGenerationTest {

  private static final Path GENERATED_CRD =
      Path.of("target/classes/META-INF/fabric8/wardenpolicies.warden.mnemo.io-v1.yml");

  @Test
  void generatedCrdMarksTimezoneRequired() throws IOException {
    String yaml = Files.readString(GENERATED_CRD);
    assertTrue(yaml.contains("kind: \"WardenPolicy\""), "generated CRD must declare the WardenPolicy kind");
    assertTrue(
        yaml.matches("(?s).*required:\\s*\\n\\s*-\\s*\"timezone\".*"),
        "generated CRD's spec schema must list timezone under required");
  }
}
