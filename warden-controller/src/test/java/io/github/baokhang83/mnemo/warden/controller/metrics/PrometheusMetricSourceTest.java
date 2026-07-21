package io.github.baokhang83.mnemo.warden.controller.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Exercises the JSON extraction directly against hand-built Prometheus-shaped responses, mirroring
 * {@code PodResizeClientTest}'s own pattern — no live HTTP round trip needed.
 */
class PrometheusMetricSourceTest {

  @Test
  void extractsTheLatestValueFromAVectorResult() throws IOException {
    String json =
        """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"__name__":"up"},"value":[1435781451.781,"1"]}
        ]}}
        """;

    assertEquals(OptionalDouble.of(1.0), PrometheusMetricSource.extractValue(json));
  }

  @Test
  void isEmptyWhenTheResultVectorIsEmpty() throws IOException {
    String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

    assertEquals(OptionalDouble.empty(), PrometheusMetricSource.extractValue(json));
  }

  @Test
  void throwsWhenTheStatusIsNotSuccess() {
    String json = "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"invalid parameter\"}";

    IOException e = assertThrows(IOException.class, () -> PrometheusMetricSource.extractValue(json));
    assertTrue(e.getMessage().contains("non-success"));
  }

  @Test
  void queryUriEncodesThePromQlExpression() {
    URI uri = PrometheusMetricSource.queryUri(URI.create("http://prometheus:9090"), "vector(1)");

    assertEquals("http://prometheus:9090/api/v1/query?query=vector%281%29", uri.toString());
  }
}
