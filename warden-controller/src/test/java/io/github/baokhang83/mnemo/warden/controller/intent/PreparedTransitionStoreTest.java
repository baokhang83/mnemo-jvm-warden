package io.github.baokhang83.mnemo.warden.controller.intent;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.github.baokhang83.mnemo.warden.crd.*;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import org.junit.jupiter.api.Test;

class PreparedTransitionStoreTest {
  private final ResizeIntent intent = new ResizeIntent("transition", "request", "pod", "app", "cid", 100, 400, 200);
  private WardenBoost request() {
    var boost = new WardenBoost();
    boost.setMetadata(new ObjectMetaBuilder().withName("b").withUid("request").build());
    var status = new WardenBoostStatus(); status.setPodUid("pod"); status.setContainerId("cid");
    status.setTransitionId(intent.transitionId()); status.setPreparedIntent(intent.encode()); boost.setStatus(status);
    return boost;
  }
  @Test void returnsOnlyTheCompleteMatchingSnapshot() {
    assertEquals(intent, PreparedTransitionStore.current(request()));
    var stale = request(); stale.getStatus().setTransitionId("other");
    assertThrows(IllegalStateException.class, () -> PreparedTransitionStore.current(stale));
    var replaced = request(); replaced.getStatus().setContainerId("new-container");
    assertThrows(IllegalStateException.class, () -> PreparedTransitionStore.current(replaced));
    var wrongRequest = request(); wrongRequest.getMetadata().setUid("other");
    assertThrows(IllegalStateException.class, () -> PreparedTransitionStore.current(wrongRequest));
    var missing = request(); missing.getStatus().setPreparedIntent(null);
    assertThrows(IllegalArgumentException.class, () -> PreparedTransitionStore.current(missing));
  }
}
