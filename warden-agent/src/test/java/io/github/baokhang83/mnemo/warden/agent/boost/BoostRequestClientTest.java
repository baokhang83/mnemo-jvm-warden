package io.github.baokhang83.mnemo.warden.agent.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Exercises the response-shape parsing directly against hand-built API-server-shaped JSON,
 * mirroring {@code PodIntentReaderTest}'s own pattern &mdash; no live HTTP round trip needed (the
 * project doesn't unit-test the HTTP layer itself; see {@code deploy/verify-boost-local-api.py}
 * for the real-cluster coverage of the actual network calls).
 */
class BoostRequestClientTest {

  @Test
  void parsesANewlyCreatedBoostWithNoStatusYetAsPending() {
    String json =
        """
        {"metadata":{"name":"my-request","resourceVersion":"111"}}
        """;

    BoostSnapshot snapshot = BoostRequestClient.parseSnapshot(json);

    assertEquals("my-request", snapshot.name());
    assertEquals("111", snapshot.resourceVersion());
    assertEquals("Pending", snapshot.phase());
    assertNull(snapshot.message());
  }

  @Test
  void parsesAGrantedBoostWithConditions() {
    String json =
        """
        {"metadata":{"name":"my-request","resourceVersion":"222"},
         "status":{"phase":"Granted","conditions":[
           {"type":"Admitted","status":"True","message":"first"},
           {"type":"Prepared","status":"True","message":"second"}
         ]}}
        """;

    BoostSnapshot snapshot = BoostRequestClient.parseSnapshot(json);

    assertEquals("Granted", snapshot.phase());
    assertEquals("second", snapshot.message(), "surfaces the most recent condition's message");
  }

  @Test
  void isEmptyMessageWhenNoConditionsArePresent() {
    String json = "{\"metadata\":{\"name\":\"r\",\"resourceVersion\":\"1\"},\"status\":{\"phase\":\"Rejected\"}}";

    BoostSnapshot snapshot = BoostRequestClient.parseSnapshot(json);

    assertEquals("Rejected", snapshot.phase());
    assertNull(snapshot.message());
  }
}
