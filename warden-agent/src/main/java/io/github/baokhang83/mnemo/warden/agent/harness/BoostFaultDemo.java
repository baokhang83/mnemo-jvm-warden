package io.github.baokhang83.mnemo.warden.agent.harness;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.baokhang83.mnemo.warden.agent.heap.Collector;
import io.github.baokhang83.mnemo.warden.agent.heap.GcCapabilities;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.intent.CorrelatedResize;
import io.github.baokhang83.mnemo.warden.agent.intent.PodIntentReader;
import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import io.github.baokhang83.mnemo.warden.protocol.ResizeAcknowledgement;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Disposable fault fixture. Uses the production correlated sequence and Kubernetes resize
 * client against its own ZGC JVM, but injects a soft-max failure for the first transition.
 * This is not the production sidecar or a security-boundary test.
 */
public final class BoostFaultDemo implements HeapController {
  private final HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
  private volatile boolean failSoftMax;
  private String failedTransition;
  @Override public long currentRss() throws IOException {
    return Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.current")).trim());
  }
  @Override public void setSoftMax(long bytes) {
    if (failSoftMax) throw new IllegalStateException("InjectedJvmPreparationFailure");
    diagnostics.setVMOption("SoftMaxHeapSize", Long.toString(bytes));
  }
  @Override public void deepGcAndUncommit(Duration timeout) throws InterruptedException {
    System.gc(); Thread.sleep(Math.min(2000, timeout.toMillis()));
  }
  @Override public GcCapabilities capabilities() { return new GcCapabilities(Collector.ZGC, true, true); }
  @Override public long softMaxBytes() { return Long.parseLong(diagnostics.getVMOption("SoftMaxHeapSize").getValue()); }
  @Override public long maxHeapBytes() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax(); }

  public static void main(String[] args) throws Exception {
    String pod = System.getenv("WARDEN_POD_NAME");
    var reader = PodIntentReader.forInClusterAgent(pod, "app");
    var heap = new BoostFaultDemo();
    System.out.println("FAULT_DEMO_READY");
    while (true) {
      try {
        var state = reader.read();
        if (state.correlatedIntent().isPresent()) {
          var intent = state.correlatedIntent().get();
          if (heap.failedTransition == null) heap.failedTransition = intent.transitionId();
          heap.failSoftMax = heap.failedTransition.equals(intent.transitionId());
          try {
            var ack = CorrelatedResize.execute(intent, pod, heap, PodResizeClient.forInClusterAgent(intent),
                Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(30), reader::read, Clock.systemUTC());
            reader.acknowledge(reader.read(), ack);
            System.out.println("APPLIED " + intent.transitionId());
          } catch (IllegalStateException failure) {
            if (!"InjectedJvmPreparationFailure".equals(failure.getMessage())) throw failure;
            var fresh = reader.read();
            CorrelatedResize.requireCurrent(intent, fresh);
            long request = fresh.currentRequestBytes().orElse(0);
            long limit = fresh.currentLimitBytes().orElse(0);
            if (request == intent.requestBytes() && limit == intent.limitBytes())
              System.out.println("INJECTED_AFTER_GROWTH " + request + " " + limit);
            reader.acknowledge(fresh, new ResizeAcknowledgement(intent, false, "InjectedJvmFailure",
                request, limit, 0, Instant.now()));
          }
        }
      } catch (Exception e) { System.out.println("RETRY " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
      Thread.sleep(2000);
    }
  }
}
