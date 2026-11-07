import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.controller.ControllerConfig;
import io.github.baokhang83.mnemo.warden.controller.WardenPolicyReconciler;
import io.github.baokhang83.mnemo.warden.controller.boost.WardenBoostReconciler;
import io.github.baokhang83.mnemo.warden.crd.*;
import io.github.baokhang83.mnemo.warden.protocol.*;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.lang.reflect.Proxy;
import java.time.Instant;

/** Real API-server lifecycle; synthetic acknowledgements stand in for the agent. */
public class BoostLifecycleProbe {
  @SuppressWarnings("unchecked") static <T extends io.fabric8.kubernetes.api.model.HasMetadata> Context<T> context(KubernetesClient client) {
    return (Context<T>) Proxy.newProxyInstance(BoostLifecycleProbe.class.getClassLoader(), new Class<?>[]{Context.class},
        (proxy, method, args) -> { if (method.getName().equals("getClient")) return client; throw new UnsupportedOperationException(method.getName()); });
  }
  static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  static void acknowledge(KubernetesClient client, String namespace, ResizeIntent intent) {
    var ack = new ResizeAcknowledgement(intent, true, "Applied", intent.requestBytes(), intent.limitBytes(), intent.heapBytes(), Instant.now());
    String patch = "{\"metadata\":{\"annotations\":{\"" + ResizeIntent.ACK_ANNOTATION + "\":" + JsonString.quote(ack.encode()) + "}}}";
    client.pods().inNamespace(namespace).withName("worker").patch(PatchContext.of(PatchType.JSON_MERGE), patch);
  }
  static void fail(KubernetesClient client, String namespace, ResizeIntent intent, String reason) {
    var ack = new ResizeAcknowledgement(intent, false, reason, intent.requestBytes(), intent.limitBytes(), 0, Instant.now());
    String patch = "{\"metadata\":{\"annotations\":{\"" + ResizeIntent.ACK_ANNOTATION + "\":" + JsonString.quote(ack.encode()) + "}}}";
    client.pods().inNamespace(namespace).withName("worker").patch(PatchContext.of(PatchType.JSON_MERGE), patch);
  }
  static boolean reason(WardenBoost boost, String value) {
    return boost.getStatus().getConditions().stream().anyMatch(c -> value.equals(c.getReason()));
  }
  public static void main(String[] args) {
    try (var client = new KubernetesClientBuilder().build()) {
      String ns = args[0];
      var requests = client.resources(WardenBoost.class).inNamespace(ns).withName("request");
      var policies = client.resources(WardenPolicy.class).inNamespace(ns).withName("policy");
      var pods = client.pods().inNamespace(ns).withName("worker");
      var lifecycle = new WardenBoostReconciler(java.time.Clock.systemUTC(), ignored -> true);
      var policy = new WardenPolicyReconciler(new ControllerConfig(java.util.Optional.empty()));
      new WardenBoostReconciler(java.time.Clock.systemUTC(), ignored -> false).reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Pending
          && requests.get().getStatus().getConditions().stream().anyMatch(c -> "UnsupportedControllerTopology".equals(c.getReason())),
          "unsupported controller topology admitted a request or hid the reason");
      // Recover a crash after claiming the pod but before recording admission.
      io.github.baokhang83.mnemo.warden.controller.boost.TargetClaim.acquire(client, pods.get(), requests.get().getMetadata().getUid());
      lifecycle = new WardenBoostReconciler(java.time.Clock.systemUTC(), ignored -> true);
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Preparing, "admission recovery failed");
      var competitor = client.resources(WardenBoost.class).inNamespace(ns).withName("competitor");
      lifecycle.reconcile(competitor.get(), context(client));
      require(competitor.get().getStatus().getPhase() == BoostPhase.Rejected, "competing claim accepted");
      lifecycle = new WardenBoostReconciler(java.time.Clock.systemUTC(), ignored -> true); // restart with durable status and claim only
      require(pods.get().getMetadata().getAnnotations().containsKey("warden.mnemo.io/boost-claim"), "no claim");
      policy.reconcile(policies.get(), context(client));
      var prepared = requests.get().getStatus().getPreparedIntent();
      require(prepared != null, "policy did not prepare first intent");
      var first = ResizeIntent.decode(prepared);
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Preparing, "granted without acknowledgement");
      fail(client, ns, first, "UnsupportedJvm");
      lifecycle.reconcile(requests.get(), context(client));
      require(reason(requests.get(), "UnsupportedJvm"), "unsupported JVM reason not surfaced");
      acknowledge(client, ns, first);
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Granted, "matching evidence did not grant");
      require(Long.toString(first.heapBytes()).equals(requests.get().getStatus().getAppliedHeapTarget()), "grant omitted applied heap evidence");
      requests.patch(PatchContext.of(PatchType.JSON_MERGE), "{\"spec\":{\"completionRequested\":true}}");
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Releasing, "completion not recorded");
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Releasing, "old evidence completed release");
      policy.reconcile(policies.get(), context(client));
      var release = ResizeIntent.decode(requests.get().getStatus().getPreparedIntent());
      require(!release.transitionId().equals(first.transitionId()), "release was not re-correlated");
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Releasing, "stale evidence completed release");
      fail(client, ns, release, "VerificationFailed");
      lifecycle.reconcile(requests.get(), context(client));
      require(reason(requests.get(), "ReleaseBlocked"), "blocked release reason not surfaced");
      // A newer schedule cannot be bypassed by evidence for the earlier release target.
      policies.patch(PatchContext.of(PatchType.JSON_MERGE),
          "{\"spec\":{\"profiles\":{\"normal\":{\"request\":\"128Mi\"}}}}");
      acknowledge(client, ns, release);
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Releasing, "release ignored a newer schedule");
      policies.patch(PatchContext.of(PatchType.JSON_MERGE),
          "{\"spec\":{\"profiles\":{\"normal\":{\"request\":\"64Mi\"}}}}");
      lifecycle.reconcile(requests.get(), context(client));
      require(requests.get().getStatus().getPhase() == BoostPhase.Completed, "release did not complete");
      lifecycle.reconcile(requests.get(), context(client));
      var annotations = pods.get().getMetadata().getAnnotations();
      require(!annotations.containsKey("warden.mnemo.io/boost-claim") && !annotations.containsKey(ResizeIntent.ANNOTATION), "claim cleanup failed");
      // An acquisition that never received permission may clean up partial preparation
      // automatically after timeout; it must still wait for a fresh release acknowledgement.
      var partial = new WardenBoost();
      partial.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName("partial").withNamespace(ns).build());
      var partialSpec = client.getKubernetesSerialization().clone(requests.get().getSpec());
      partialSpec.setCompletionRequested(false); partial.setSpec(partialSpec);
      client.resources(WardenBoost.class).inNamespace(ns).resource(partial).create();
      var partials = client.resources(WardenBoost.class).inNamespace(ns).withName("partial");
      lifecycle.reconcile(partials.get(), context(client));
      require(partials.get().getStatus().getPhase() == BoostPhase.Preparing, "partial admission failed");
      policy.reconcile(policies.get(), context(client));
      partials.subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE),
          "{\"status\":{\"acquisitionDeadline\":\"2020-01-01T00:00:00Z\"}}");
      lifecycle.reconcile(partials.get(), context(client));
      require(partials.get().getStatus().getPhase() == BoostPhase.Failed, "timeout did not fail acquisition");
      lifecycle.reconcile(partials.get(), context(client));
      require(partials.get().getStatus().getPhase() == BoostPhase.Releasing, "failed acquisition did not start cleanup");
      policy.reconcile(policies.get(), context(client));
      var cleanup = ResizeIntent.decode(partials.get().getStatus().getPreparedIntent());
      lifecycle.reconcile(partials.get(), context(client));
      require(partials.get().getStatus().getPhase() == BoostPhase.Releasing, "cleanup completed without evidence");
      acknowledge(client, ns, cleanup);
      lifecycle.reconcile(partials.get(), context(client));
      require(partials.get().getStatus().getPhase() == BoostPhase.Completed, "partial cleanup did not complete");
      lifecycle.reconcile(partials.get(), context(client));
      System.out.println("PASS: admission, competing claims, restart, grant, release, and partial-acquisition recovery");
    }
  }
}
