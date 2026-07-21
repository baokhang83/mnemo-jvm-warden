package io.github.baokhang83.mnemo.warden.controller.schedule;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the shorthand duration strings {@code LeadTime.shrink}/{@code .warm} already use in
 * every example policy (e.g. {@code "5m"}, {@code "10m"}, {@code "30s"}) &mdash; not ISO-8601
 * ({@code "PT5M"}), which {@link Duration#parse} requires and no sample policy has ever used.
 */
final class ShorthandDuration {

  private static final Pattern SHORTHAND = Pattern.compile("^(\\d+)([smhd])$");

  private ShorthandDuration() {}

  static Duration parse(String value) {
    Matcher matcher = SHORTHAND.matcher(value.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not a recognized duration shorthand (e.g. \"5m\"): " + value);
    }

    long amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2)) {
      case "s" -> Duration.ofSeconds(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default -> throw new AssertionError("unreachable: pattern only matches [smhd]");
    };
  }
}
