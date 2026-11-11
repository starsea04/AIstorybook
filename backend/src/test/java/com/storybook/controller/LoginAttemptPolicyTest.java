package com.storybook.controller;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptPolicyTest {
  private final Instant now = Instant.parse("2026-11-02T00:00:00Z");

  @Test
  void locksOnFifthConsecutiveFailureForFifteenMinutes() {
    var fourth = LoginAttemptPolicy.afterFailure(3, null, now);
    assertEquals(4, fourth.failureCount());
    assertNull(fourth.lockedUntil());

    var fifth = LoginAttemptPolicy.afterFailure(fourth.failureCount(), fourth.lockedUntil(), now);
    assertEquals(5, fifth.failureCount());
    assertEquals(now.plusSeconds(900), fifth.lockedUntil());
    assertTrue(LoginAttemptPolicy.isLocked(fifth.lockedUntil(), now));
  }

  @Test
  void expiredLockStartsANewFailureSequence() {
    var next = LoginAttemptPolicy.afterFailure(5, now, now);
    assertEquals(1, next.failureCount());
    assertNull(next.lockedUntil());
    assertFalse(LoginAttemptPolicy.isLocked(now, now));
  }
}
