package io.github.baokhang83.mnemo.warden.agent.resize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MinimalJsonTest {

  @Test
  void parsesScalars() {
    assertEquals("hello", MinimalJson.parse("\"hello\""));
    assertEquals(42.0, MinimalJson.parse("42"));
    assertEquals(-3.5, MinimalJson.parse("-3.5"));
    assertEquals(Boolean.TRUE, MinimalJson.parse("true"));
    assertEquals(Boolean.FALSE, MinimalJson.parse("false"));
    assertNull(MinimalJson.parse("null"));
  }

  @Test
  void parsesStringEscapes() {
    assertEquals("a\"b\\c/d\nE", MinimalJson.parse("\"a\\\"b\\\\c\\/d\\nE\""));
    assertEquals("A", MinimalJson.parse("\"\\u0041\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void parsesNestedObjectsAndArrays() {
    Object parsed = MinimalJson.parse("{\"a\":[1,2,{\"b\":\"c\"}],\"d\":{}}");

    Map<String, Object> root = (Map<String, Object>) parsed;
    List<Object> a = (List<Object>) root.get("a");
    assertEquals(1.0, a.get(0));
    assertEquals(2.0, a.get(1));
    assertEquals("c", ((Map<String, Object>) a.get(2)).get("b"));
    assertTrue(((Map<String, Object>) root.get("d")).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void parsesARealCapturedPodStatusFragment() {
    // Captured verbatim from a real kind cluster's GET response after a resize PATCH.
    String json =
        """
        {
          "status": {
            "containerStatuses": [
              {
                "name": "app",
                "resources": {
                  "limits": {"cpu": "200m", "memory": "333Mi"},
                  "requests": {"cpu": "100m", "memory": "200Mi"}
                }
              }
            ]
          }
        }
        """;

    Map<String, Object> root = (Map<String, Object>) MinimalJson.parse(json);
    Map<String, Object> status = (Map<String, Object>) root.get("status");
    List<Object> containerStatuses = (List<Object>) status.get("containerStatuses");
    Map<String, Object> app = (Map<String, Object>) containerStatuses.get(0);
    Map<String, Object> resources = (Map<String, Object>) app.get("resources");
    Map<String, Object> requests = (Map<String, Object>) resources.get("requests");

    assertEquals("200Mi", requests.get("memory"));
  }

  @Test
  void rejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("{\"a\":}"));
    assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("{\"a\":1"));
    assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("not json"));
  }
}
