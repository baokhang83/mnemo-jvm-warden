package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * The outcome of {@link DeepGc#runAndAwaitUncommit}.
 *
 * @param bytesUncommitted committed heap freed, measured from just before {@code GC.run} to the
 *     last sample taken
 * @param completed whether the committed heap size stabilized before the caller's timeout, as
 *     opposed to the timeout being hit while it was still dropping
 */
public record UncommitResult(long bytesUncommitted, boolean completed) {}
