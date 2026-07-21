package io.github.baokhang83.mnemo.warden.agent.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.baokhang83.mnemo.warden.agent.heap.GcCapabilities;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizeTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the ordering with hand-rolled fakes, mirroring {@code ShrinkSequenceTest} &mdash;
 * pure orchestration logic, no platform behavior to prove here.
 */
class GrowSequenceTest {

  private static final Duration RESIZE_TIMEOUT = Duration.ofSeconds(30);

  @Test
  void resizesTheCgroupBeforeRaisingSoftMax() throws Exception {
    List<String> callOrder = new ArrayList<>();
    FakeHeapController heap = new FakeHeapController(callOrder);
    FakeResizePort resizeClient = new FakeResizePort(callOrder);
    GrowSequence sequence = new GrowSequence(heap, resizeClient, "my-pod", "app", RESIZE_TIMEOUT);

    GrowOutcome outcome = sequence.growTo(150L * 1024 * 1024, 200L * 1024 * 1024);

    assertEquals(200L * 1024 * 1024, outcome.confirmedLimitBytes());

    assertEquals(1, resizeClient.calls.size(), "grow must resize exactly once");
    FakeResizePort.Call call = resizeClient.calls.get(0);
    assertEquals("my-pod", call.podName);
    assertEquals("app", call.containerName);
    assertEquals(150L * 1024 * 1024, call.requestBytes);
    assertEquals(200L * 1024 * 1024, call.limitBytes);
    assertEquals(RESIZE_TIMEOUT, call.timeout);

    assertEquals(List.of("resizeMemory", "setSoftMax"), callOrder, "cgroup must go up before SoftMax is raised");
    assertEquals(200L * 1024 * 1024, heap.lastSoftMax, "SoftMax is raised to the new limit, not the request");
  }

  @Test
  void aKubeletTimeoutPropagatesWithoutTouchingSoftMax() {
    List<String> callOrder = new ArrayList<>();
    FakeHeapController heap = new FakeHeapController(callOrder);
    FakeResizePort resizeClient = new FakeResizePort(callOrder);
    resizeClient.timeoutOnNextCall = true;
    GrowSequence sequence = new GrowSequence(heap, resizeClient, "my-pod", "app", RESIZE_TIMEOUT);

    assertThrows(
        ResizeTimeoutException.class, () -> sequence.growTo(150L * 1024 * 1024, 200L * 1024 * 1024));

    assertEquals(List.of("resizeMemory"), callOrder, "SoftMax must never be raised on an unconfirmed resize");
  }

  private static final class FakeHeapController implements HeapController {
    private final List<String> callOrder;
    Long lastSoftMax;

    FakeHeapController(List<String> callOrder) {
      this.callOrder = callOrder;
    }

    @Override
    public long currentRss() {
      throw new UnsupportedOperationException("GrowSequence has no verification gate to read RSS for");
    }

    @Override
    public void setSoftMax(long bytes) {
      callOrder.add("setSoftMax");
      lastSoftMax = bytes;
    }

    @Override
    public void deepGcAndUncommit(Duration timeout) {
      throw new UnsupportedOperationException("grow never GCs, only shrink does");
    }

    @Override
    public GcCapabilities capabilities() {
      // GrowSequence must stay GC-blind (§2), same invariant as ShrinkSequence.
      throw new UnsupportedOperationException("GrowSequence must not query collector capabilities");
    }
  }

  private static final class FakeResizePort implements ResizePort {
    record Call(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout) {}

    private final List<String> callOrder;
    final List<Call> calls = new ArrayList<>();
    boolean timeoutOnNextCall;

    FakeResizePort(List<String> callOrder) {
      this.callOrder = callOrder;
    }

    @Override
    public void resizeMemory(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout) {
      callOrder.add("resizeMemory");
      calls.add(new Call(podName, containerName, requestBytes, limitBytes, timeout));
      if (timeoutOnNextCall) {
        throw new ResizeTimeoutException(podName, containerName);
      }
    }
  }
}
