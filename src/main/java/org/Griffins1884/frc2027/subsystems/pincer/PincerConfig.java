package org.Griffins1884.frc2027.subsystems.pincer;

/**
 * Logical pincer configuration.
 *
 * <p>The {@code maxActuatorOutput} value must come from team-approved mechanism characterization or
 * review. Do not guess hardware limits in code.
 */
public record PincerConfig(
    double maxActuatorOutput, boolean leftJawInverted, boolean rightJawInverted) {
  public PincerConfig {
    if (!Double.isFinite(maxActuatorOutput)
        || maxActuatorOutput <= 0.0
        || maxActuatorOutput > 1.0) {
      throw new IllegalArgumentException(
          "maxActuatorOutput must be finite and in the range (0.0, 1.0]");
    }
  }
}
