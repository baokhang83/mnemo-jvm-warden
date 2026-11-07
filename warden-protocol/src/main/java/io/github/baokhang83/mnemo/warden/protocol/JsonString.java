package io.github.baokhang83.mnemo.warden.protocol;

/** JSON string escaping for bounded annotation patches, without a runtime JSON library. */
public final class JsonString {
  private JsonString() {}

  public static String quote(String value) {
    if (value == null) throw new IllegalArgumentException("missing JSON string");
    StringBuilder result = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        default -> {
          if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
          else result.append(c);
        }
      }
    }
    return result.append('"').toString();
  }
}
