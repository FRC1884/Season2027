package org.Griffins1884.frc2027.subsystems.elevator;

/**
 * Disconnected placeholder for two mechanically linked Kraken X60s. No controllers are constructed
 * or commanded; readiness and allowed-height checks remain false.
 *
 * <p>Before implementing real hardware IO:
 *
 * <ul>
 *   <li>TODO: Confirm controller models and install the approved vendor library.
 *   <li>TODO: Supply both CAN IDs, CAN bus, verified directions and leader/follower arrangement.
 *   <li>TODO: Supply approved current limits, neutral mode and output limits.
 *   <li>TODO: Supply gearing and carriage travel per motor rotation, including elevator staging.
 *   <li>TODO: Confirm sensors, homing/reference procedure and physical/soft travel limits.
 *   <li>TODO: Supply measured preset heights, motion constraints and closed-loop/feedforward
 *       tuning.
 *   <li>TODO: Define fault handling for either motor, feedback loss and limit activation.
 *   <li>TODO: Review gravity support and safe behavior on cancellation, disable and power loss.
 *   <li>TODO: Apply and verify both controllers' configurations before reporting readiness.
 * </ul>
 */
public final class ElevatorIOKrakenX60 implements ElevatorIO {
  @Override
  public void setHeightMeters(double heightMeters) {
    // Intentionally disconnected, including when a caller supplies a finite height.
  }

  @Override
  public void stop() {
    // Intentionally disconnected: there are no controllers or active position requests.
  }
}
