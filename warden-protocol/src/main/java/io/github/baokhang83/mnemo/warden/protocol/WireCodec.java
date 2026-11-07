package io.github.baokhang83.mnemo.warden.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Bounded, versioned annotation encoding. Field framing cannot collide with container IDs. */
final class WireCodec {
  private WireCodec() {}

  static String encode(String... fields) {
    StringBuilder result = new StringBuilder("v1");
    for (String field : fields) {
      if (field == null || field.isBlank()) throw new IllegalArgumentException("missing protocol field");
      result.append('.').append(Base64.getUrlEncoder().withoutPadding()
          .encodeToString(field.getBytes(StandardCharsets.UTF_8)));
    }
    if (result.length() > 8192) throw new IllegalArgumentException("protocol payload exceeds 8192 characters");
    return result.toString();
  }

  static String[] decode(String encoded, int count) {
    if (encoded == null || encoded.length() > 8192) throw new IllegalArgumentException("invalid protocol payload size");
    String[] parts = encoded.split("\\.", -1);
    if (parts.length != count + 1 || !parts[0].equals("v1"))
      throw new IllegalArgumentException("unsupported protocol version or field count");
    String[] fields = new String[count];
    for (int i = 0; i < count; i++) {
      fields[i] = new String(Base64.getUrlDecoder().decode(parts[i + 1]), StandardCharsets.UTF_8);
      if (fields[i].isBlank()) throw new IllegalArgumentException("missing protocol field");
    }
    if (!encode(fields).equals(encoded)) throw new IllegalArgumentException("noncanonical protocol payload");
    return fields;
  }

  static void identity(String value) {
    if (value == null || value.isBlank() || value.length() > 256)
      throw new IllegalArgumentException("invalid protocol identity");
  }
}
