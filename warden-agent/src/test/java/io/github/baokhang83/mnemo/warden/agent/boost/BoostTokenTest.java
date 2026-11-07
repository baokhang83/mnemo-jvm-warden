package io.github.baokhang83.mnemo.warden.agent.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoostTokenTest {

  @Test
  void generatesDifferentTokensEachTime() {
    assertNotEquals(BoostToken.generate(), BoostToken.generate());
  }

  @Test
  void writesCreatingParentDirectories(@TempDir Path dir) throws Exception {
    Path tokenFile = dir.resolve("nested/boost/token");
    String token = BoostToken.generate();

    BoostToken.write(tokenFile, token);

    assertEquals(token, Files.readString(tokenFile));
  }

  @Test
  void matchesTheExactPresentedToken() {
    String token = BoostToken.generate();
    assertTrue(BoostToken.matches(token, token));
  }

  @Test
  void rejectsAWrongToken() {
    assertFalse(BoostToken.matches(BoostToken.generate(), BoostToken.generate()));
  }

  @Test
  void rejectsAMissingToken() {
    assertFalse(BoostToken.matches(null, BoostToken.generate()));
  }
}
