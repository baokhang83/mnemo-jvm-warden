package io.github.baokhang83.mnemo.warden.protocol;

import java.time.Instant;
import java.util.Objects;

/** Evidence is valid only for the exact intent and still-current target identity. */
public record ResizeAcknowledgement(ResizeIntent intent, boolean applied, String reason,
    long requestBytes, long limitBytes, long heapBytes, Instant completedAt) {
  public ResizeAcknowledgement {
    Objects.requireNonNull(intent);
    Objects.requireNonNull(completedAt);
    WireCodec.identity(reason);
    if (requestBytes < 0 || limitBytes < 0 || heapBytes < 0)
      throw new IllegalArgumentException("negative observation");
  }

  public boolean confirms(ResizeIntent expected, String podUid, String containerId,
      long currentRequest, long currentLimit) {
    return applied && intent.equals(expected) && expected.podUid().equals(podUid)
        && expected.containerId().equals(containerId)
        && requestBytes == expected.requestBytes() && limitBytes == expected.limitBytes()
        && heapBytes == expected.heapBytes()
        && currentRequest == requestBytes && currentLimit == limitBytes;
  }

  public String encode() {
    return WireCodec.encode(intent.encode(), Boolean.toString(applied), reason,
        Long.toString(requestBytes), Long.toString(limitBytes), Long.toString(heapBytes), completedAt.toString());
  }

  public static ResizeAcknowledgement decode(String encoded) {
    String[] f = WireCodec.decode(encoded, 7);
    if (!f[1].equals("true") && !f[1].equals("false")) throw new IllegalArgumentException("invalid outcome");
    return new ResizeAcknowledgement(ResizeIntent.decode(f[0]), Boolean.parseBoolean(f[1]), f[2],
        Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5]), Instant.parse(f[6]));
  }
}
