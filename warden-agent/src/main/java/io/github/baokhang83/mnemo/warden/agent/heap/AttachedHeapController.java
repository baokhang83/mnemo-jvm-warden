package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The real {@link HeapController}: composes {@link SoftMax}, {@link DeepGc}, and {@link
 * RssReader} over a single attached target &mdash; the assembly {@code HeapController}'s own
 * javadoc has been pointing at since M1 ("gets assembled once the M2 resize state machine
 * actually needs the whole contract").
 *
 * <p>{@link #forTarget(AttachedJvm)} detects the collector once and rejects it up front if it
 * doesn't support uncommit at all (constitution: an unsupported collector should leave the agent
 * read-only, per {@link GcCapabilities#supported()}'s javadoc) &mdash; callers of {@code
 * HeapController} never see that check, only the failure to construct one.
 */
public final class AttachedHeapController implements HeapController {

  private static final String OPERATION = "M2 shrink sequence";

  private final SoftMax softMax; // null on G1 — capabilities.supportsSoftMax() is false there
  private final DeepGc deepGc;
  private final RssReader rssReader;
  private final GcCapabilities capabilities;

  private AttachedHeapController(SoftMax softMax, DeepGc deepGc, RssReader rssReader, GcCapabilities capabilities) {
    this.softMax = softMax;
    this.deepGc = deepGc;
    this.rssReader = rssReader;
    this.capabilities = capabilities;
  }

  /** @throws UnsupportedCollectorException if the target's collector cannot uncommit at all */
  public static AttachedHeapController forTarget(AttachedJvm target) throws IOException {
    return forTarget(target, Path.of(RssReader.HOST_CGROUP_ROOT));
  }

  /**
   * Package-private seam so tests (and CI, which runs the target directly on the runner rather
   * than in a pod with the {@code hostPath} mount) can point cgroup resolution at a real {@code
   * /sys/fs/cgroup} instead of the deployment-only {@link RssReader#HOST_CGROUP_ROOT} &mdash;
   * mirrors exactly how {@code RssReaderTest} exercises {@link RssReader} itself.
   */
  static AttachedHeapController forTarget(AttachedJvm target, Path hostCgroupRoot) throws IOException {
    List<GarbageCollectorMXBean> gcBeans =
        ManagementFactory.getPlatformMXBeans(target.mbeanConnection(), GarbageCollectorMXBean.class);
    GcCapabilities capabilities = GcDetector.detect(gcBeans);
    if (!capabilities.supported()) {
      throw new UnsupportedCollectorException(OPERATION, capabilities.collector());
    }

    SoftMax softMax = capabilities.supportsSoftMax() ? SoftMax.forTarget(target) : null;
    DeepGc deepGc = DeepGc.forTarget(target);
    Path cgroupRoot = RssReader.resolveCgroupRoot(target.pid(), hostCgroupRoot);
    RssReader rssReader = RssReader.forTarget(target.pid(), cgroupRoot, target.mbeanConnection());
    return new AttachedHeapController(softMax, deepGc, rssReader, capabilities);
  }

  @Override
  public long currentRss() throws IOException {
    return rssReader.currentRss().workingSetBytes();
  }

  @Override
  public void setSoftMax(long bytes) {
    if (softMax != null) {
      softMax.setSoftMaxHeapSize(bytes);
    }
  }

  @Override
  public void deepGcAndUncommit(Duration timeout) throws IOException, InterruptedException {
    deepGc.runAndAwaitUncommit(timeout);
  }

  @Override
  public GcCapabilities capabilities() {
    return capabilities;
  }
}
