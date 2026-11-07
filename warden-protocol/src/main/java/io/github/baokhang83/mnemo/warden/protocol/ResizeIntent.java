package io.github.baokhang83.mnemo.warden.protocol;

/** Immutable identity and resource target for one application-container transition. */
public record ResizeIntent(String transitionId, String requestUid, String podUid,
    String containerName, String containerId, long requestBytes, long limitBytes, long heapBytes) {
  public static final String ANNOTATION = "warden.mnemo.io/resize-intent";
  public static final String ACK_ANNOTATION = "warden.mnemo.io/resize-ack";

  public ResizeIntent {
    for (String identity : new String[] {transitionId, requestUid, podUid, containerName, containerId})
      WireCodec.identity(identity);
    if (requestBytes <= 0 || requestBytes > limitBytes || heapBytes <= 0 || heapBytes >= limitBytes)
      throw new IllegalArgumentException("invalid memory or heap target");
  }

  public String encode() {
    return WireCodec.encode(transitionId, requestUid, podUid, containerName, containerId,
        Long.toString(requestBytes), Long.toString(limitBytes), Long.toString(heapBytes));
  }

  public static ResizeIntent decode(String encoded) {
    String[] f = WireCodec.decode(encoded, 8);
    return new ResizeIntent(f[0], f[1], f[2], f[3], f[4],
        Long.parseLong(f[5]), Long.parseLong(f[6]), Long.parseLong(f[7]));
  }
}
