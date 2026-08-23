package org.Griffins1884.frc2027.subsystems.pincer;

import com.revrobotics.spark.SparkFlex;
import edu.wpi.first.math.MathUtil;
import java.util.Objects;

/** Adapts a NEO Vortex's preconfigured SPARK Flex controller for pincer use. */
public final class SparkFlexPincerMotorIO implements PincerMotorIO {
  private final SparkFlex motor;

  /**
   * Creates an adapter around an existing controller.
   *
   * <p>The caller must configure and approve CAN ID, current limits, idle mode, and any hardware or
   * software limits before passing the controller here.
   */
  public SparkFlexPincerMotorIO(SparkFlex motor) {
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
