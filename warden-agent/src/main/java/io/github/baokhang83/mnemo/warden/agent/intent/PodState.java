package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import java.util.Optional;
import java.util.OptionalLong;

/** One GET observation; desired spec is never substituted for applied container status. */
public record PodState(Optional<Intent> intent, OptionalLong currentLimitBytes,
    OptionalLong currentRequestBytes, String podUid, String containerId, String resourceVersion,
    Optional<ResizeIntent> correlatedIntent, String acknowledgement) {
  public PodState(Optional<Intent> intent, OptionalLong currentLimitBytes) {
    this(intent, currentLimitBytes, OptionalLong.empty(), null, null, null, Optional.empty(), null);
  }
}
