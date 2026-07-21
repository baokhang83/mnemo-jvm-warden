package io.github.baokhang83.mnemo.warden.agent.resize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader: enough to navigate the Kubernetes API's response shape ({@code
 * status.containerStatuses[].resources}) and nothing more &mdash; no writer, no streaming, no
 * generic (de)serialization.
 *
 * <p>Exists in preference to a JSON library because {@link PodResizeClient} only ever needs to
 * read a handful of string leaves out of a known response shape; a dependency-free ~150-line
 * reader keeps that need in proportion, matching the agent's zero-runtime-dependency
 * architecture (the same reasoning that ruled out Fabric8 for the HTTP layer itself).
 *
 * <p>Parses into plain JDK types: {@link Map}&lt;String,Object&gt; for objects, {@link
 * List}&lt;Object&gt; for arrays, {@link String}, {@link Double}, {@link Boolean}, or {@code
 * null} for scalars &mdash; callers navigate with ordinary casts, exactly like consuming any
 * other parsed-tree JSON API.
 *
 * <p>Every character read goes through {@link #peek()}/{@link #next()}, which check bounds and
 * throw {@link IllegalArgumentException} on truncated input &mdash; not raw {@code
 * String.charAt}, which would let an unchecked {@code StringIndexOutOfBoundsException} escape
 * instead (caught by a test feeding truncated JSON, e.g. {@code "{\"a\":1"} with no closing
 * brace).
 *
 * <p>Public, not package-private: {@code io.github.baokhang83.mnemo.warden.agent.intent}
 * (W-306) reads the same API server response shape (a pod's annotations and container status)
 * and reuses this rather than duplicating a second minimal JSON reader in the same module.
 */
public final class MinimalJson {

  private final String source;
  private int pos;

  private MinimalJson(String source) {
    this.source = source;
  }

  public static Object parse(String json) {
    MinimalJson parser = new MinimalJson(json);
    parser.skipWhitespace();
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (parser.pos != json.length()) {
      throw new IllegalArgumentException("trailing content after JSON value at index " + parser.pos);
    }
    return value;
  }

  private Object parseValue() {
    char c = peek();
    return switch (c) {
      case '{' -> parseObject();
      case '[' -> parseArray();
      case '"' -> parseString();
      case 't', 'f' -> parseBoolean();
      case 'n' -> parseNull();
      default -> parseNumber();
    };
  }

  private Map<String, Object> parseObject() {
    Map<String, Object> result = new LinkedHashMap<>();
    expect('{');
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      String key = parseString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      result.put(key, parseValue());
      skipWhitespace();
      char c = next();
      if (c == '}') {
        return result;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected ',' or '}' at index " + (pos - 1));
      }
    }
  }

  private List<Object> parseArray() {
    List<Object> result = new ArrayList<>();
    expect('[');
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      result.add(parseValue());
      skipWhitespace();
      char c = next();
      if (c == ']') {
        return result;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected ',' or ']' at index " + (pos - 1));
      }
    }
  }

  private String parseString() {
    expect('"');
    StringBuilder result = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return result.toString();
      }
      if (c != '\\') {
        result.append(c);
        continue;
      }
      char escaped = next();
      switch (escaped) {
        case '"' -> result.append('"');
        case '\\' -> result.append('\\');
        case '/' -> result.append('/');
        case 'b' -> result.append('\b');
        case 'f' -> result.append('\f');
        case 'n' -> result.append('\n');
        case 'r' -> result.append('\r');
        case 't' -> result.append('\t');
        case 'u' -> {
          if (pos + 4 > source.length()) {
            throw new IllegalArgumentException("truncated \\u escape at index " + (pos - 2));
          }
          result.append((char) Integer.parseInt(source.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw new IllegalArgumentException("invalid escape '\\" + escaped + "' at index " + (pos - 2));
      }
    }
  }

  private Boolean parseBoolean() {
    if (source.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (source.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    throw new IllegalArgumentException("invalid literal at index " + pos);
  }

  private Object parseNull() {
    if (source.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    throw new IllegalArgumentException("invalid literal at index " + pos);
  }

  private Double parseNumber() {
    int start = pos;
    while (pos < source.length() && "-+.eE0123456789".indexOf(source.charAt(pos)) >= 0) {
      pos++;
    }
    if (pos == start) {
      throw new IllegalArgumentException("expected a value at index " + pos);
    }
    return Double.parseDouble(source.substring(start, pos));
  }

  private char peek() {
    if (pos >= source.length()) {
      throw new IllegalArgumentException("unexpected end of input at index " + pos);
    }
    return source.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char c) {
    if (peek() != c) {
      throw new IllegalArgumentException("expected '" + c + "' at index " + pos);
    }
    pos++;
  }

  private void skipWhitespace() {
    while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
      pos++;
    }
  }
}
