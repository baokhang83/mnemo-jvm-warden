import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.baokhang83.mnemo.warden.agent.heap.*;
import io.github.baokhang83.mnemo.warden.agent.intent.*;
import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.Map;

/** Live API/heap probe. Runs as the target pod; not the full two-container Warden deployment. */
public class BoostProtocolProbe implements HeapController {
  final HotSpotDiagnosticMXBean diagnostics = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
  public long currentRss() { throw new UnsupportedOperationException("probe only exercises growth"); }
  public void setSoftMax(long bytes) { diagnostics.setVMOption("SoftMaxHeapSize", Long.toString(bytes)); }
  public void deepGcAndUncommit(Duration timeout) { throw new UnsupportedOperationException("probe only exercises growth"); }
  public GcCapabilities capabilities() { return new GcCapabilities(Collector.ZGC, true, true); }
  public long softMaxBytes() { return Long.parseLong(diagnostics.getVMOption("SoftMaxHeapSize").getValue()); }
  public long maxHeapBytes() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax(); }

  public static void main(String[] args) throws Exception {
    var reader = PodIntentReader.forInClusterAgent(System.getenv("POD_NAME"), "app");
    var heap = new BoostProtocolProbe();
    System.out.println("PROBE_READY");
    while (true) {
      try {
        var state = reader.read();
        if (state.correlatedIntent().isPresent()) {
          var intent = state.correlatedIntent().get();
          var ack = CorrelatedResize.execute(intent, System.getenv("POD_NAME"), heap,
              PodResizeClient.forInClusterAgent(intent), Map.of(), Duration.ofSeconds(5),
              Duration.ofSeconds(30), reader::read, Clock.systemUTC());
          reader.acknowledge(reader.read(), ack);
          System.out.println("APPLIED " + intent.transitionId());
        }
      } catch (Exception e) {
        System.out.println("RETRY " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      Thread.sleep(2000);
    }
  }
}
