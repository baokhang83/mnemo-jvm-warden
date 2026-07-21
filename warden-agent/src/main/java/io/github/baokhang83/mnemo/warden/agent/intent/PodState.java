package io.github.baokhang83.mnemo.warden.agent.intent;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * What {@link PodIntentReader} observed on one read: the controller's intent, if any annotation
 * is present, and the target container's actual current memory limit, if the kubelet has
 * reported one.
 */
public record PodState(Optional<Intent> intent, OptionalLong currentLimitBytes) {}
