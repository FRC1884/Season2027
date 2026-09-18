package org.Griffins1884.frc2027.subsystems.elevator;

/** Hardware boundary for one elevator carriage driven by two mechanically linked motors. */
public interface ElevatorIO {
  /**
   * True only when both controllers are configured, feedback is healthy, the position reference is
   * established, and all required motion protections are active. Reevaluate during operation.
   */
  default boolean isReady() {
    return false;
  }

  /**
   * True only for a target within the verified travel range and permitted by current interlocks.
   */
  default boolean isHeightAllowed(double heightMeters) {
    return false;
  }

  /**
   * Requests a common carriage height in meters, not independent motor targets. A real
   * implementation must enforce readiness, travel and hardware limits continuously, coordinate the
   * motors using verified directions, and stop both on a fault. This interface supplies no control
   * tuning.
   */
  void setHeightMeters(double heightMeters);

  /** Cancels position control and stops both motors, even with incomplete configuration. */
  void stop();
}
