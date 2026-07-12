package io.github.baokhang83.mnemo.warden.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Proves the module compiles and its JUnit 5 test wiring runs. Real tests replace this. */
class SmokeTest {

  @Test
  void moduleIsOnTheClasspath() {
    assertTrue(getClass().getPackageName().startsWith("io.github.baokhang83.mnemo.warden"));
  }
}
