package io.github.baokhang83.mnemo.warden.controller.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;

/**
 * Evaluates a PromQL instant query ({@code GET /api/v1/query}) and returns its latest value
 * (W-401) &mdash; the read half of M4's guardrails; nothing acts on the value here (W-402,
 * W-403).
 *
 * <p>An empty result vector (no matching series right now &mdash; e.g. genuinely no traffic) is
 * a normal, valid outcome, not an error: returns {@link OptionalDouble#empty()}. Only a
 * non-{@code success} response status or an HTTP-level failure throws.
 */
public final class PrometheusMetricSource {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PrometheusMetricSource() {}

  /** Queries {@code promQl} against the Prometheus instance at {@code baseUri}. */
  public static OptionalDouble query(HttpClient httpClient, URI baseUri, String promQl)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(queryUri(baseUri, promQl)).GET().build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Prometheus query failed: HTTP " + response.statusCode() + " " + response.body());
    }
    return extractValue(response.body());
  }

  static URI queryUri(URI baseUri, String promQl) {
    return baseUri.resolve("/api/v1/query?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
  }

  /**
   * Package-private and static, taking the raw JSON response body directly &mdash; mirrors
   * {@code PodResizeClient.extractConfirmedMemory}'s existing pattern, so tests exercise the
   * real parsing logic without a live HTTP round trip.
   */
  static OptionalDouble extractValue(String responseBody) throws IOException {
    JsonNode root = MAPPER.readTree(responseBody);
    if (!"success".equals(root.path("status").asText())) {
      throw new IOException("Prometheus query returned a non-success status: " + responseBody);
    }
    JsonNode result = root.path("data").path("result");
    if (!result.isArray() || result.isEmpty()) {
      return OptionalDouble.empty();
    }
    JsonNode value = result.get(0).path("value");
    if (!value.isArray() || value.size() < 2) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(Double.parseDouble(value.get(1).asText()));
  }
}
