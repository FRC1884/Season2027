package org.Griffins1884.frc2027.subsystems.claw;

/**
 * Disconnected placeholder for two Kraken X60 motors. No controllers are constructed or commanded.
 *
 * <p>Before implementing real hardware IO:
 *
 * <ul>
 *   <li>TODO: Confirm each motor's controller model and install its approved vendor library.
 *   <li>TODO: Supply the left and right CAN IDs and CAN bus.
 *   <li>TODO: Supply each motor's approved current limits and neutral mode.
 *   <li>TODO: Verify each roller's inward direction and configure inversion independently.
 *   <li>TODO: Apply and verify both configurations before reporting isConfigured() as true.
 * </ul>
 */
public final class ClawIOKrakenX60 implements ClawIO {
  @Override
  public void setRollerOutputs(double leftOutput, double rightOutput) {
    // Intentionally disconnected, including when a caller supplies nonzero outputs.
  }
}
