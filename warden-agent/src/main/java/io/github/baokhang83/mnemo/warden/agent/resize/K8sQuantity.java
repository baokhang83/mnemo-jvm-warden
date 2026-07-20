package io.github.baokhang83.mnemo.warden.agent.resize;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Kubernetes resource {@code Quantity} string into a byte count.
 *
 * <p>Exists because the API server normalizes memory quantities in {@code status}: a value
 * {@link PodResizeClient} PATCHes as a plain byte count (e.g. {@code "209715200"}) comes back in
 * {@code status.containerStatuses[].resources} as a different string for the same value (e.g.
 * {@code "200Mi"}) &mdash; verified against a real target. Confirming a resize therefore requires
 * parsing both sides to bytes and comparing numerically; string equality would never match, even
 * on success.
 *
 * <p>Covers the suffixes the API server actually emits for memory (binary: Ki/Mi/Gi/Ti/Pi/Ei;
 * decimal: k/M/G/T/P/E; or a bare integer) &mdash; not the full Quantity grammar (no exponential
 * notation), since that's all {@link PodResizeClient} ever needs to read back.
 */
public final class K8sQuantity {

  private static final Map<String, BigDecimal> BINARY_SUFFIXES =
      Map.of(
          "Ki", BigDecimal.valueOf(1024L),
          "Mi", BigDecimal.valueOf(1024L * 1024),
          "Gi", BigDecimal.valueOf(1024L * 1024 * 1024),
          "Ti", BigDecimal.valueOf(1024L * 1024 * 1024 * 1024),
          "Pi", BigDecimal.valueOf(1024L * 1024 * 1024 * 1024 * 1024),
          "Ei", BigDecimal.valueOf(1024L * 1024 * 1024 * 1024 * 1024 * 1024));

  private static final Map<String, BigDecimal> DECIMAL_SUFFIXES =
      Map.of(
          "k", BigDecimal.valueOf(1_000L),
          "M", BigDecimal.valueOf(1_000_000L),
          "G", BigDecimal.valueOf(1_000_000_000L),
          "T", BigDecimal.valueOf(1_000_000_000_000L),
          "P", BigDecimal.valueOf(1_000_000_000_000_000L),
          "E", BigDecimal.valueOf(1_000_000_000_000_000_000L));

  private static final Pattern QUANTITY = Pattern.compile("^(\\d+(?:\\.\\d+)?)([A-Za-z]*)$");

  private K8sQuantity() {}

  /** Parses a Quantity string (e.g. {@code "200Mi"}, {@code "1500000000"}) into a byte count. */
  public static long parseBytes(String quantity) {
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
