package io.github.baokhang83.mnemo.warden.agent.heap;

import java.util.OptionalLong;

/**
 * A single RSS reading for the target.
 *
 * @param cgroupMemoryCurrent the raw {@code memory.current} value, in bytes &mdash; includes
 *     reclaimable page cache, so it is not by itself a trustworthy pressure signal
 * @param workingSetBytes {@code cgroupMemoryCurrent} minus {@code memory.stat}'s
 *     {@code inactive_file} &mdash; the non-reclaimable footprint; this is the number to gate
 *     resize decisions on
 * @param nmtCommittedBytes the target's Native Memory Tracking committed total, if NMT was
 *     enabled on the target; empty otherwise (best-effort reconciliation, never required)
 */
public record RssReading(long cgroupMemoryCurrent, long workingSetBytes, OptionalLong nmtCommittedBytes) {}
