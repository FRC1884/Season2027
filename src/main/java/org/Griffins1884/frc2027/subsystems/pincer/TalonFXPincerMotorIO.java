package org.Griffins1884.frc2027.subsystems.pincer;

import com.ctre.phoenix6.hardware.TalonFX;
import edu.wpi.first.math.MathUtil;
import java.util.Objects;

/** Adapts a Kraken X60's preconfigured Talon FX controller for pincer use. */
public final class TalonFXPincerMotorIO implements PincerMotorIO {
  private final TalonFX motor;

  /**
   * Creates an adapter around an existing controller.
   *
   * <p>The caller must configure and approve CAN ID, current limits, neutral mode, and any hardware
   * or software limits before passing the controller here.
   */
  public TalonFXPincerMotorIO(TalonFX motor) {
    this.motor = Objects.requireNonNull(motor, "motor");
  }

  @Override
  public void set(double output) {
    if (!Double.isFinite(output)) {
      stop();
      return;
    }

    motor.set(MathUtil.clamp(output, -1.0, 1.0));
  }

  @Override
  public void stop() {
    motor.stopMotor();
  }
}
