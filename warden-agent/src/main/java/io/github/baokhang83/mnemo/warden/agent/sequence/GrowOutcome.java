package io.github.baokhang83.mnemo.warden.agent.sequence;

/**
 * The single exit state of {@link GrowSequence#growTo}. Unlike {@link ShrinkOutcome}, this is a
 * plain record, not a sealed interface: grow has no verification gate to fail, so its two
 * failure modes ({@code ResizeTimeoutException}, {@code IOException}) are already distinct
 * exceptions rather than alternate success states a caller would need to branch on.
 */
public record GrowOutcome(long confirmedLimitBytes) {}
