import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.intent.PreparedTransitionStore;
import io.github.baokhang83.mnemo.warden.crd.*;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.TargetRef;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;

/** Real API-server check for the per-pod schedule/Boost annotation handoff. */
public class BoostFloorProbe {
  public static void main(String[] args) throws Exception {
    String namespace = args[0], name = args[1];
    try (var client = new KubernetesClientBuilder().build()) {
      var resource = client.pods().inNamespace(namespace).withName(name);
      var pod = resource.get();
      var status = pod.getStatus().getContainerStatuses().getFirst();
      var request = new WardenBoost();
      request.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("boost").withNamespace(namespace).build());
      var requestSpec = new WardenBoostSpec(); var podRef = new BoostPodRef();
      podRef.setName(name); podRef.setUid(pod.getMetadata().getUid());
      requestSpec.setPodRef(podRef); requestSpec.setPolicyRef("policy"); requestSpec.setContainer("app");
      requestSpec.setBoost("maintenance"); request.setSpec(requestSpec);
      client.resources(WardenBoost.class).inNamespace(namespace).resource(request).create();
      var boosts = client.resources(WardenBoost.class).inNamespace(namespace).withName("boost");
      String statusJson = "{\"status\":{\"phase\":\"Preparing\",\"policyUid\":\"policy-uid\","
          + "\"podUid\":" + io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(pod.getMetadata().getUid())
          + ",\"containerId\":" + io.github.baokhang83.mnemo.warden.protocol.JsonString.quote(status.getContainerID())
          + ",\"requestFloor\":\"64Mi\",\"limitFloor\":\"256Mi\",\"heapTarget\":\"32Mi\",\"transitionId\":\"old\"}}";
      boosts.subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE), statusJson);
      var boost = boosts.get();
      var intent = new ResizeIntent("initial", boost.getMetadata().getUid(), pod.getMetadata().getUid(), "app",
          status.getContainerID(), 67108864L, 268435456L, 33554432L);
      PreparedTransitionStore.prepare(client, boost, intent); // crash before first pod publication
      var policy = new WardenPolicy();
      policy.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("policy").withNamespace(namespace).withUid("policy-uid").build());
      var ref = new TargetRef(); ref.setKind("Pod"); ref.setName(name);
      var initial = new ResourceProfile(); initial.setRequest("64Mi"); initial.setLimit("256Mi");
      IntentEmitter.emit(client, namespace, ref, initial, policy);
      if (!PreparedTransitionStore.current(boosts.get()).equals(intent))
        throw new AssertionError("status must contain the transition before pod publication");
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(intent))
        throw new AssertionError("pod did not receive persisted transition");
      var stale = boosts.get();
      var newer = new ResizeIntent("newer", intent.requestUid(), intent.podUid(), "app", intent.containerId(),
          intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
      PreparedTransitionStore.prepareAndPublish(client, stale, newer);
      try {
        PreparedTransitionStore.prepareAndPublish(client, stale, intent);
        throw new AssertionError("stale publisher was accepted");
      } catch (io.fabric8.kubernetes.client.KubernetesClientException expected) { }
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(newer))
        throw new AssertionError("stale publisher overwrote newer transition");
      var recovery = new ResizeIntent("recovery", intent.requestUid(), intent.podUid(), "app", intent.containerId(),
          intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
      PreparedTransitionStore.prepare(client, boosts.get(), recovery); // simulate crash before pod patch
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(newer))
        throw new AssertionError("preparation unexpectedly modified pod");
      PreparedTransitionStore.prepareAndPublish(client, boosts.get(), recovery);
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(recovery))
        throw new AssertionError("reconcile failed to publish persisted transition");
      var peak = new ResourceProfile(); peak.setRequest("96Mi"); peak.setLimit("320Mi"); peak.setHeapTarget("64Mi");
      var crossedSchedule = new ResourceProfile();
      crossedSchedule.setRequest("128Mi"); crossedSchedule.setLimit("256Mi"); crossedSchedule.setHeapTarget("48Mi");
      var low = new ResourceProfile(); low.setRequest("32Mi"); low.setLimit("300Mi"); low.setHeapTarget("32Mi");
      var scheduleWindow = new ScheduleWindow(); scheduleWindow.setCron("* * * * *"); scheduleWindow.setProfile("schedule");
      var policySpec = new WardenPolicySpec(); policySpec.setTimezone("UTC"); policySpec.setTargetRef(ref);
      policySpec.setProfiles(java.util.Map.of("emergency", peak, "schedule", crossedSchedule, "low", low));
      policySpec.setSchedule(java.util.List.of(scheduleWindow));
      var guard = new Guardrail(); guard.setMetric("load"); guard.setEmergencyGrowAbove("50"); guard.setShrinkBelow("50");
      policySpec.setGuardrail(guard); policy.setSpec(policySpec);
      var metric = new java.util.concurrent.atomic.AtomicReference<>("99");
      var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
      var serverThread = new Thread(() -> {
        while (!server.isClosed()) {
          try (var connection = server.accept()) {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()));
            while (true) { var line = reader.readLine(); if (line == null || line.isEmpty()) break; }
            var payload = "{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[0,\"" + metric.get() + "\"]}]}}";
            var bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var output = connection.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
          } catch (java.io.IOException e) { if (!server.isClosed()) throw new RuntimeException(e); }
        }
      }); serverThread.setDaemon(true); serverThread.start();
      @SuppressWarnings("unchecked")
      var context = (io.javaoperatorsdk.operator.api.reconciler.Context<WardenPolicy>) java.lang.reflect.Proxy.newProxyInstance(
          BoostFloorProbe.class.getClassLoader(), new Class<?>[]{io.javaoperatorsdk.operator.api.reconciler.Context.class},
          (proxy, method, arguments) -> { if (method.getName().equals("getClient")) return client; throw new UnsupportedOperationException(method.getName()); });
      var reconciler = new io.github.baokhang83.mnemo.warden.controller.WardenPolicyReconciler(
          new io.github.baokhang83.mnemo.warden.controller.ControllerConfig(java.util.Optional.of(
              java.net.URI.create("http://127.0.0.1:" + server.getLocalPort()))));
      // A lower profile limit is vetoed for ordinary pods, but the admitted floor makes
      // this correlated candidate a grow relative to the actual pod allocation.
      guard.setEmergencyGrowAbove(null);
      var prior = new WardenPolicyStatus(); prior.setCurrentProfile("emergency"); policy.setStatus(prior);
      reconciler.reconcile(policy, context);
      var floorGrowth = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (floorGrowth.requestBytes() != 134217728L || floorGrowth.limitBytes() != 268435456L
          || floorGrowth.heapBytes() != 50331648L || !"emergency".equals(policy.getStatus().getCurrentProfile()))
        throw new AssertionError("profile veto incorrectly suppressed floor-resolved growth");
      guard.setEmergencyGrowAbove("50");
      reconciler.reconcile(policy, context);
      var grown = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (grown.requestBytes() != 134217728L || grown.limitBytes() != 335544320L
          || grown.heapBytes() != 67108864L
          || !grown.requestUid().equals(intent.requestUid()) || grown.transitionId().equals(intent.transitionId()))
        throw new AssertionError("correlated growth not emitted correctly");
      if (!PreparedTransitionStore.current(boosts.get()).equals(grown))
        throw new AssertionError("policy writer did not persist the new transition");
      // A wrong policy identity must never lower or replace the active target.
      var wrongPolicy = new WardenPolicy();
      wrongPolicy.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
          .withName("policy").withUid("other-policy-uid").build());
      try {
        IntentEmitter.emit(client, namespace, ref, peak, wrongPolicy);
        throw new AssertionError("mismatched policy identity was accepted");
      } catch (IllegalStateException expected) { }
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(grown))
        throw new AssertionError("mismatched policy changed the pod");
      scheduleWindow.setProfile("low"); guard.setEmergencyGrowAbove(null);
      reconciler.reconcile(policy, context);
      var held = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (!held.equals(grown)) throw new AssertionError("guardrail allowed active shrink");
      metric.set("0"); reconciler.reconcile(policy, context);
      var activeShrink = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (activeShrink.requestBytes() != 67108864L || activeShrink.limitBytes() != 314572800L
          || activeShrink.heapBytes() != 33554432L || !activeShrink.equals(PreparedTransitionStore.current(boosts.get())))
        throw new AssertionError("active schedule did not shrink to the admitted floor");
      guard.setEmergencyGrowAbove("50"); metric.set("99"); scheduleWindow.setProfile("schedule");
      reconciler.reconcile(policy, context);
      grown = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (grown.requestBytes() != 134217728L || grown.limitBytes() != 335544320L || grown.heapBytes() != 67108864L)
        throw new AssertionError("emergency failed to restore combined targets");
      scheduleWindow.setProfile("low"); guard.setEmergencyGrowAbove(null);
      boosts.subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE),
          "{\"status\":{\"phase\":\"Releasing\",\"conditions\":[{\"type\":\"WorkFinished\","
              + "\"status\":\"True\",\"reason\":\"Complete\",\"message\":\"work finished\","
              + "\"lastTransitionTime\":\"2026-01-01T00:00:00Z\"}]}}");
      reconciler.reconcile(policy, context);
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(grown))
        throw new AssertionError("guardrail did not veto release shrink");
      // Persist while quiet, crash before publication, then observe high load while the policy
      // status already names the low profile. The pod's applied allocation is authoritative.
      var pendingRelease = new ResizeIntent("pending-release", grown.requestUid(), grown.podUid(),
          grown.containerName(), grown.containerId(), 33554432L, 314572800L, 33554432L);
      PreparedTransitionStore.prepare(client, boosts.get(), pendingRelease);
      policy.getStatus().setCurrentProfile("low");
      reconciler.reconcile(policy, context);
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(grown))
        throw new AssertionError("recovery bypassed current shrink veto");
      metric.set("0"); reconciler.reconcile(policy, context);
      var release = ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
      if (release.requestBytes() != 33554432L || release.limitBytes() != 314572800L
          || release.heapBytes() != 33554432L || !release.equals(PreparedTransitionStore.current(boosts.get())))
        throw new AssertionError("verified completion did not persist the current policy release target");
      var blackout = new BlackoutWindow();
      blackout.setStart(java.time.Instant.now().minusSeconds(60).toString());
      blackout.setEnd(java.time.Instant.now().plusSeconds(60).toString());
      policySpec.setBlackout(java.util.List.of(blackout));
      guard.setEmergencyGrowAbove("50"); metric.set("99");
      reconciler.reconcile(policy, context);
      if (!ResizeIntent.decode(resource.get().getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)).equals(release))
        throw new AssertionError("blackout allowed a transition");
      server.close();
      System.out.println("PASS: reconciler crossed emergency, durable floor, release veto, release, and blackout");
    }
  }
}
