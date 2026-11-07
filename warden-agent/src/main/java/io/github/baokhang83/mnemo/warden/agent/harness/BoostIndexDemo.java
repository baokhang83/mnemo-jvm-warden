package io.github.baokhang83.mnemo.warden.agent.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable B7 workload: a queryable index with controllable rebuild and retained live heap. */
public final class BoostIndexDemo {
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static volatile List<String> index = List.of("initial");
  private static volatile List<byte[]> retained = List.of();
  private static volatile CountDownLatch workGate = new CountDownLatch(0);
  private static volatile long runs;
  private static volatile long preparedRuns;
  private static volatile long scanPasses;
  private static volatile long checksum;
  private static volatile long elapsedMillis;
  private static volatile long buildMillis;
  private static volatile String metricValue = "0";
  private BoostIndexDemo() {}

  public static void main(String[] args) throws IOException {
    var server = HttpServer.create(new InetSocketAddress(8089), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/status", x -> reply(x, 200, status()));
    server.createContext("/api/v1/query", x -> reply(x, 200,
        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"value\":[0,\"" + metricValue + "\"]}]}}"));
    server.createContext("/metric/high", x -> { metricValue = "99"; reply(x, 200, "{}"); });
    server.createContext("/metric/low", x -> { metricValue = "0"; reply(x, 200, "{}"); });
    server.createContext("/query", x -> reply(x, 200, "{\"entries\":" + index.size() + "}"));
    server.createContext("/rebuild", x -> {
      if (!"POST".equals(x.getRequestMethod())) { reply(x, 405, "{}"); return; }
      if (!RUNNING.compareAndSet(false, true)) { reply(x, 409, "{}"); return; }
      workGate = new CountDownLatch(1);
      Thread.ofPlatform().name("index-rebuild").start(BoostIndexDemo::rebuild);
      reply(x, 202, "{\"started\":true}");
    });
    server.createContext("/finish", x -> { workGate.countDown(); reply(x, 200, "{}"); });
    server.createContext("/retain", x -> {
      if (RUNNING.get()) { reply(x, 409, "{}"); return; }
      try {
        var chunks = new ArrayList<byte[]>();
        for (int i = 0; i < 320; i++) {
          byte[] chunk = new byte[1024 * 1024];
          for (int p = 0; p < chunk.length; p += 4096) chunk[p] = (byte) (i + p);
          chunks.add(chunk);
        }
        retained = chunks;
        reply(x, 200, "{\"retainedMiB\":320}");
      } catch (OutOfMemoryError e) { reply(x, 503, "{\"error\":\"allocation failed\"}"); }
    });
    server.createContext("/clear", x -> { retained = List.of(); reply(x, 200, "{}"); });
    server.start();
    System.out.println("boost-index-demo ready");
  }

  private static void rebuild() {
    long started = System.nanoTime();
    try {
      var entries = new ArrayList<String>(120_000);
      for (int i = 119_999; i >= 0; i--) entries.add("document-" + i);
      Collections.sort(entries);
      index = List.copyOf(entries);
      buildMillis = (System.nanoTime() - started) / 1_000_000;
      preparedRuns++;
      // Continue scanning the new index while queries run until the test ends the work.
      while (!workGate.await(5, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        long value = 0;
        for (String entry : index) value += entry.hashCode();
        checksum = value;
        scanPasses++;
      }
      elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      runs++;
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    finally { RUNNING.set(false); }
  }

  private static String status() {
    var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long softMax = -1;
    try {
      var diagnostic = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
      softMax = Long.parseLong(diagnostic.getVMOption("SoftMaxHeapSize").getValue());
    } catch (RuntimeException ignored) { }
    long rss = -1;
    try (var lines = java.nio.file.Files.lines(java.nio.file.Path.of("/proc/self/status"))) {
      rss = lines.filter(line -> line.startsWith("VmRSS:")).mapToLong(line ->
          Long.parseLong(line.substring(6).trim().split("\\s+")[0]) * 1024).findFirst().orElse(-1);
    } catch (IOException | RuntimeException ignored) { }
    return "{\"javaVersion\":\"" + System.getProperty("java.version") + "\",\"running\":" + RUNNING.get() + ",\"runs\":" + runs + ",\"entries\":" + index.size()
        + ",\"preparedRuns\":" + preparedRuns + ",\"scanPasses\":" + scanPasses
        + ",\"checksum\":" + checksum + ",\"buildMillis\":" + buildMillis
        + ",\"elapsedMillis\":" + elapsedMillis + ",\"retainedMiB\":" + retained.size()
        + ",\"heapUsedBytes\":" + heap.getUsed() + ",\"heapCommittedBytes\":" + heap.getCommitted()
        + ",\"softMaxBytes\":" + softMax + ",\"rssBytes\":" + rss + "}";
  }
  private static void reply(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }
}
