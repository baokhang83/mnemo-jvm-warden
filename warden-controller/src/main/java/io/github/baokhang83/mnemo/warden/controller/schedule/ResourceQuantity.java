package io.github.baokhang83.mnemo.warden.controller.schedule;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Kubernetes resource {@code Quantity} string into a byte count, for comparing two
 * {@code ResourceProfile} limits to classify a schedule transition's direction (W-304).
 *
 * <p>Deliberately a small, controller-local copy of {@code warden-agent}'s {@code K8sQuantity},
 * not a shared dependency: the two modules are deliberately decoupled (each module's own {@code
 * pom.xml} says so), and the agent's copy is tied to a different problem (confirming a PATCHed
 * value against the API server's normalized echo) than this one (comparing two static spec
 * values) — see W-304's design doc.
 */
final class ResourceQuantity {

  private static final Map<String, BigDecimal> BINARY_SUFFIXES =
      Map.of(
          "Ki", BigDecimal.valueOf(1024L),
          "Mi", BigDecimal.valueOf(1024L * 1024),
          "Gi", BigDecimal.valueOf(1024L * 1024 * 1024),
          "Ti", BigDecimal.valueOf(1024L * 1024 * 1024 * 1024));

  private static final Map<String, BigDecimal> DECIMAL_SUFFIXES =
      Map.of(
          "k", BigDecimal.valueOf(1_000L),
          "M", BigDecimal.valueOf(1_000_000L),
          "G", BigDecimal.valueOf(1_000_000_000L),
          "T", BigDecimal.valueOf(1_000_000_000_000L));

  private static final Pattern QUANTITY = Pattern.compile("^(\\d+(?:\\.\\d+)?)([A-Za-z]*)$");

  private ResourceQuantity() {}

  static long parseBytes(String quantity) {
    Matcher matcher = QUANTITY.matcher(quantity.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not a recognized Quantity: " + quantity);
    }

    BigDecimal number = new BigDecimal(matcher.group(1));
    String suffix = matcher.group(2);
    if (suffix.isEmpty()) {
      return number.longValueExact();
    }

    BigDecimal multiplier = BINARY_SUFFIXES.get(suffix);
    if (multiplier == null) {
      multiplier = DECIMAL_SUFFIXES.get(suffix);
    }
    if (multiplier == null) {
      throw new IllegalArgumentException("unsupported Quantity suffix: " + suffix + " in " + quantity);
    }
    return number.multiply(multiplier).longValueExact();
  }
}
