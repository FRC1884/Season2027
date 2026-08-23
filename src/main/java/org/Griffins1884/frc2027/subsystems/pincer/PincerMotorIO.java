package org.Griffins1884.frc2027.subsystems.pincer;

/**
 * Minimal motor contract for one pincer jaw.
 *
 * <p>Vendor-specific adapters are responsible for translating these normalized outputs into the
 * underlying controller API.
 */
public interface PincerMotorIO {
  /**
   * Sets the normalized jaw output.
   *
   * @param output normalized output in the range {@code [-1.0, 1.0]}
   */
  void set(double output);

  /** Stops the jaw motor output. */
  void stop();
}
