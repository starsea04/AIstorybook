package com.storybook.controller;

import java.time.Duration;
import java.time.Instant;

final class LoginAttemptPolicy {
  static final int MAX_FAILURES = 5;
  static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  record FailureState(int failureCount, Instant lockedUntil) {}

  private LoginAttemptPolicy() {}

  static boolean isLocked(Instant lockedUntil, Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  static FailureState afterFailure(int currentFailures, Instant currentLock, Instant now) {
    int count = currentLock != null && !currentLock.isAfter(now) ? 0 : currentFailures;
    int failures = count + 1;
    return new FailureState(failures, failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null);
  }
}
