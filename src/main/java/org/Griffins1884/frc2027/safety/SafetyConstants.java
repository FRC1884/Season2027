package org.Griffins1884.frc2027.safety;

/**
 * Safety-critical constants protected by CODEOWNERS and branch policy.
 *
 * <p>Keep robot-wide hard limits here rather than scattering them through mechanisms. Changes
 * require mentor/software-lead review and must include validation evidence.
 */
public final class SafetyConstants {
  private SafetyConstants() {}

  public static final double MAX_BATTERY_VOLTAGE_VOLTS = 12.0;
  public static final double DEFAULT_MOTOR_OUTPUT_LIMIT = 0.80;
  public static final boolean ALLOW_AUTOMATED_DEPLOY = false;
}
