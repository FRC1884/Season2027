package org.Griffins1884.frc2027.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafetyConstantsTest {
  @Test
  void defaultMotorLimitStaysWithinSafeRange() {
    assertTrue(SafetyConstants.DEFAULT_MOTOR_OUTPUT_LIMIT > 0.0);
    assertTrue(SafetyConstants.DEFAULT_MOTOR_OUTPUT_LIMIT <= 1.0);
  }

  @Test
  void automatedDeployIsDisabledByDefault() {
    assertFalse(SafetyConstants.ALLOW_AUTOMATED_DEPLOY);
  }
}
