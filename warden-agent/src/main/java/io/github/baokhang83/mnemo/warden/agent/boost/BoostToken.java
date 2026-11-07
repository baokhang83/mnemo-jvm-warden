package io.github.baokhang83.mnemo.warden.agent.boost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The B8 app&harr;sidecar credential: a random bearer token the agent generates once at startup
 * and writes to a file on a volume shared with the app container &mdash; not a Kubernetes
 * Secret, so acquiring it costs no extra API round trip and the SDK side needs no Kubernetes
 * credentials at all (the dev plan's explicit requirement).
 *
 * <p>Loopback binding alone is not an authorization boundary between containers sharing a pod
 * network (any process in the pod's network namespace can reach a loopback port) &mdash; this
 * token is the explicit application-to-sidecar credential that closes that gap.
 *
 * <p>The agent is the sole writer and regenerates the token on every restart; it never reads an
 * existing file back. A sidecar restart independent of the app container therefore invalidates
 * whatever the app last read &mdash; a real, accepted constraint (see {@code
 * docs/warden-boost.md}), not something this class works around: the app is expected to re-read
 * the token file per call (or on a 401) rather than cache it indefinitely.
 */
public final class BoostToken {

  private static final SecureRandom RANDOM = new SecureRandom();

  private BoostToken() {}

  /** A fresh 256-bit token, URL-safe base64 without padding. */
  public static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Writes {@code token} to {@code path}, creating parent directories as needed and restricting
   * permissions to the owner where the filesystem supports POSIX permissions (best effort &mdash;
   * some {@code emptyDir} backing filesystems don't).
   */
  public static void write(Path path, String token) throws IOException {
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, token, StandardCharsets.UTF_8);
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (e.g. some CSI/emptyDir backends on non-Linux test runners); the
      // shared volume is still scoped to this one pod, so this is a defense-in-depth best effort.
    }
  }

  /** Constant-time comparison, so response timing can't be used to guess the token byte-by-byte. */
  public static boolean matches(String presented, String expected) {
    if (presented == null || expected == null) {
      return false;
    }
    byte[] a = presented.getBytes(StandardCharsets.UTF_8);
    byte[] b = expected.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(a, b);
  }
}
