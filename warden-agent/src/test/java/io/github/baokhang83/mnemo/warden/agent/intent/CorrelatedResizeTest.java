package io.github.baokhang83.mnemo.warden.agent.intent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.baokhang83.mnemo.warden.agent.heap.*;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CorrelatedResizeTest {
  final ResizeIntent intent = new ResizeIntent("t", "r", "pod", "app", "containerd://1", 100, 400, 200);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
  final List<String> order = new ArrayList<>();
  final Heap heap = new Heap();
  PodState state;

  PodState state(long request, long limit) {
    return new PodState(Optional.empty(), OptionalLong.of(limit), OptionalLong.of(request),
        "pod", "containerd://1", "1", Optional.of(intent), null);
  }
  ResizePort resize = (pod, container, request, limit, timeout) -> {
    order.add("resize"); state = state(request, limit);
  };
  io.github.baokhang83.mnemo.warden.protocol.ResizeAcknowledgement run() throws Exception {
    return CorrelatedResize.execute(intent, "pod-name", heap, resize, Map.of(),
        Duration.ofSeconds(1), Duration.ofSeconds(1), () -> state, clock);
  }

  @Test void failureEvidenceUsesStableBoundedReasons() {
    assertEquals("UnsupportedJvm", IntentWatcher.failureReason(new IOException("UnsupportedRuntime")));
    assertEquals("UnsupportedJvm", IntentWatcher.failureReason(new IOException("UnsupportedHeapTarget")));
    assertEquals("VerificationFailed", IntentWatcher.failureReason(new IOException("VerificationFailed")));
    assertEquals("PreparationFailed", IntentWatcher.failureReason(new IOException("private details")));
  }

  @Test void growsResourcesBeforeHeapAndAcknowledgesObservedState() throws Exception {
    state = state(50, 300);
    var ack = run();
    assertEquals(List.of("resize", "heap"), order);
    assertTrue(ack.confirms(intent, "pod", "containerd://1", 100, 400));
    assertEquals(clock.instant(), ack.completedAt());
  }
  @Test void equalAllocationStillPreparesAndReadsHeap() throws Exception {
    state = state(100, 400);
    assertTrue(run().applied());
    assertEquals(List.of("heap"), order);
  }
  @Test void decreasingRequestUsesVerifiedShrinkEvenWhenLimitIsEqual() throws Exception {
    state = state(200, 400);
    run();
    assertEquals(List.of("heap", "gc", "rss", "resize"), order);
  }
  @Test void failedRssGateNeverResizesOrAcknowledgesSuccess() {
    state = state(200, 500); heap.rss = 500;
    assertThrows(IOException.class, this::run);
    assertFalse(order.contains("resize"));
  }
  @Test void failedGrowthNeverRaisesHeap() {
    state = state(50, 300);
    resize = (p, c, r, l, t) -> { throw new IOException("Deferred"); };
    assertThrows(IOException.class, this::run);
    assertTrue(order.isEmpty());
  }
  @Test void staleOrReplacedInstanceCannotPrepare() {
    state = new PodState(Optional.empty(), OptionalLong.of(400), OptionalLong.of(100),
        "pod", "containerd://2", "1", Optional.of(intent), null);
    assertThrows(IOException.class, this::run);
    assertTrue(order.isEmpty());
  }
  @Test void noEvidenceWhenStatusOrHeapDoesNotConfirm() {
    state = state(50, 300);
    resize = (p, c, r, l, t) -> {}; // successful API response without applied status
    assertThrows(IOException.class, this::run);
    state = state(100, 400); heap.soft = 50; heap.ignoreSet = true;
    assertThrows(IOException.class, this::run);
  }
  @Test void runtimeCeilingCheckedBeforeResourceMutation() {
    state = state(50, 300); heap.max = 150;
    assertThrows(IOException.class, this::run);
    assertTrue(order.isEmpty());
  }
  @Test void transitionChangedDuringResizeCannotBeAcknowledged() {
    state = state(50, 300);
    resize = (p, c, r, l, t) -> state = new PodState(Optional.empty(), OptionalLong.of(400),
        OptionalLong.of(100), "pod", "containerd://1", "2",
        Optional.of(new ResizeIntent("new", "r", "pod", "app", "containerd://1", 100, 400, 200)), null);
    assertThrows(IOException.class, this::run);
  }

  class Heap implements HeapController {
    long soft = 50, max = 1000, rss = 10;
    boolean ignoreSet;
    public long currentRss() { order.add("rss"); return rss; }
    public void setSoftMax(long bytes) { order.add("heap"); if (!ignoreSet) soft = bytes; }
    public void deepGcAndUncommit(Duration timeout) { order.add("gc"); }
    public GcCapabilities capabilities() { return new GcCapabilities(Collector.ZGC, true, true); }
    public long softMaxBytes() { return soft; }
    public long maxHeapBytes() { return max; }
  }
}
