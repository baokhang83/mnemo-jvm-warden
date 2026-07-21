package io.github.baokhang83.mnemo.warden.agent.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.heap.GcCapabilities;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the ordering and RSS verification gate with hand-rolled fakes, not mocks of a
 * concrete class &mdash; exactly what extracting {@link ResizePort} and completing {@link
 * HeapController} (W-203's other slice) was for: this is pure orchestration logic, no platform
 * behavior to prove against a real target here (that's {@code AttachedHeapControllerTest} and
 * {@code PodResizeClientTest}'s job).
 */
class ShrinkSequenceTest {

  private static final Duration GC_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration RESIZE_TIMEOUT = Duration.ofSeconds(30);

  @Test
  void completesAndResizesWhenRssVerifiesBelowTheTarget() throws Exception {
    FakeHeapController heap = new FakeHeapController(100L * 1024 * 1024);
    FakeResizePort resizeClient = new FakeResizePort();
    ShrinkSequence sequence =
        new ShrinkSequence(heap, resizeClient, "my-pod", "app", GC_TIMEOUT, RESIZE_TIMEOUT);

    ShrinkOutcome outcome = sequence.shrinkTo(150L * 1024 * 1024, 200L * 1024 * 1024);

    ShrinkOutcome.Completed completed = assertInstanceOf(ShrinkOutcome.Completed.class, outcome);
    assertEquals(100L * 1024 * 1024, completed.finalRssBytes());

    assertEquals(List.of("setSoftMax", "deepGcAndUncommit", "currentRss"), heap.calls, "must run in this order");
    assertEquals(200L * 1024 * 1024, heap.lastSoftMax, "SoftMax is set to the new limit, not the request");

    assertEquals(1, resizeClient.calls.size(), "a verified shrink must resize exactly once");
    FakeResizePort.Call call = resizeClient.calls.get(0);
    assertEquals("my-pod", call.podName);
    assertEquals("app", call.containerName);
    assertEquals(150L * 1024 * 1024, call.requestBytes);
    assertEquals(200L * 1024 * 1024, call.limitBytes);
    assertEquals(RESIZE_TIMEOUT, call.timeout);
  }

  @Test
  void abortsWithoutTouchingTheCgroupWhenRssDoesNotVerify() throws Exception {
    FakeHeapController heap = new FakeHeapController(250L * 1024 * 1024);
    FakeResizePort resizeClient = new FakeResizePort();
    ShrinkSequence sequence =
        new ShrinkSequence(heap, resizeClient, "my-pod", "app", GC_TIMEOUT, RESIZE_TIMEOUT);

    ShrinkOutcome outcome = sequence.shrinkTo(150L * 1024 * 1024, 200L * 1024 * 1024);

    ShrinkOutcome.AbortedVerificationFailed aborted =
        assertInstanceOf(ShrinkOutcome.AbortedVerificationFailed.class, outcome);
    assertEquals(250L * 1024 * 1024, aborted.observedRssBytes());
    assertEquals(200L * 1024 * 1024, aborted.targetBytes());

    assertTrue(resizeClient.calls.isEmpty(), "the cgroup must never be touched on a failed gate — §5");
  }

  @Test
  void treatsRssEqualToTheTargetAsAFailedGate() throws Exception {
    // The acceptance criteria says "verify RSS < target" — strictly less than, not <=. An RSS
    // that only just reaches the target leaves zero headroom, so it must not pass.
    FakeHeapController heap = new FakeHeapController(200L * 1024 * 1024);
    FakeResizePort resizeClient = new FakeResizePort();
    ShrinkSequence sequence =
        new ShrinkSequence(heap, resizeClient, "my-pod", "app", GC_TIMEOUT, RESIZE_TIMEOUT);

    ShrinkOutcome outcome = sequence.shrinkTo(150L * 1024 * 1024, 200L * 1024 * 1024);

    assertInstanceOf(ShrinkOutcome.AbortedVerificationFailed.class, outcome);
    assertTrue(resizeClient.calls.isEmpty());
  }

  private static final class FakeHeapController implements HeapController {
    final List<String> calls = new ArrayList<>();
    final long rssToReport;
    Long lastSoftMax;

    FakeHeapController(long rssToReport) {
      this.rssToReport = rssToReport;
    }

    @Override
    public long currentRss() {
      calls.add("currentRss");
      return rssToReport;
    }

    @Override
    public void setSoftMax(long bytes) {
      calls.add("setSoftMax");
      lastSoftMax = bytes;
    }

    @Override
    public void deepGcAndUncommit(Duration timeout) {
      calls.add("deepGcAndUncommit");
    }

    @Override
    public GcCapabilities capabilities() {
      // ShrinkSequence must stay GC-blind (§2) — it has no business asking. If this ever fires,
      // that invariant broke.
      throw new UnsupportedOperationException("ShrinkSequence must not query collector capabilities");
    }
  }

  private static final class FakeResizePort implements ResizePort {
    record Call(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout) {}

    final List<Call> calls = new ArrayList<>();

    @Override
    public void resizeMemory(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout) {
      calls.add(new Call(podName, containerName, requestBytes, limitBytes, timeout));
    }
  }
}
