package io.github.baokhang83.mnemo.warden.agent.resize;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ResizeIdentityTest {
  final String json = """
      {"metadata":{"uid":"pod-uid","resourceVersion":"42"},
       "status":{"containerStatuses":[{"name":"app","containerID":"containerd://one","state":{"running":{}}}]}}
      """;
  @Test void bindsResizeToCurrentRunningInstance() throws Exception {
    assertEquals("42", PodResizeClient.requireIdentity(json, "app", "pod-uid", "containerd://one"));
  }
  @Test void rejectsPodAndContainerReplacementOrStoppedContainer() {
    assertThrows(IOException.class, () -> PodResizeClient.requireIdentity(json, "app", "other-pod", "containerd://one"));
    assertThrows(IOException.class, () -> PodResizeClient.requireIdentity(json, "app", "pod-uid", "containerd://two"));
    assertThrows(IOException.class, () -> PodResizeClient.requireIdentity(
        json.replace("running", "terminated"), "app", "pod-uid", "containerd://one"));
  }
}
