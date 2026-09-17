package org.Griffins1884.frc2027.subsystems.claw;

/** Hardware boundary for the claw's two intake rollers. */
public interface ClawIO {
  /** True only after both motors' hardware configuration has been supplied and applied. */
  default boolean isConfigured() {
    return false;
  }

  /**
   * Sets independent roller duty cycles. Positive means inward for each roller, not a shared shaft
   * rotation direction. Hardware implementations must apply the verified per-motor directions. Zero
   * must stop each motor even when configuration is incomplete.
   */
  void setRollerOutputs(double leftOutput, double rightOutput);
}
