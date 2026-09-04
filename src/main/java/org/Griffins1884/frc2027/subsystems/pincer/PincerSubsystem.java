package org.Griffins1884.frc2027.subsystems.pincer;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.Objects;

/** Vendor-neutral two-motor pincer control. */
public class PincerSubsystem extends SubsystemBase {
  private final PincerMotorIO leftJaw;
  private final PincerMotorIO rightJaw;
  private final PincerConfig config;

  public PincerSubsystem(PincerMotorIO leftJaw, PincerMotorIO rightJaw, PincerConfig config) {
    this.leftJaw = Objects.requireNonNull(leftJaw, "leftJaw");
    this.rightJaw = Objects.requireNonNull(rightJaw, "rightJaw");
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * Closes the pincer using the requested normalized magnitude.
   *
   * <p>The applied output is clamped to the team-supplied {@link PincerConfig#maxActuatorOutput()}.
   */
  public void grab(double requestedOutput) {
    applySymmetricOutput(Math.abs(requestedOutput));
  }

  /**
   * Opens the pincer using the requested normalized magnitude.
   *
   * <p>The applied output is clamped to the team-supplied {@link PincerConfig#maxActuatorOutput()}.
   */
  public void release(double requestedOutput) {
    applySymmetricOutput(-Math.abs(requestedOutput));
  }

  /** Stops both pincer jaws. */
  public void stop() {
    leftJaw.stop();
    rightJaw.stop();
  }

  /** Runs {@link #grab(double)} until the command ends, then stops both motors. */
  public Command grabCommand(double requestedOutput) {
    return Commands.startEnd(() -> grab(requestedOutput), this::stop, this);
  }

  /** Runs {@link #release(double)} until the command ends, then stops both motors. */
  public Command releaseCommand(double requestedOutput) {
    return Commands.startEnd(() -> release(requestedOutput), this::stop, this);
  }

  /** Stops both jaws immediately. */
  public Command stopCommand() {
    return Commands.runOnce(this::stop, this);
  }

  private void applySymmetricOutput(double requestedOutput) {
    if (!Double.isFinite(requestedOutput)) {
      stop();
      return;
    }

    double limitedOutput =
        MathUtil.clamp(
            MathUtil.clamp(requestedOutput, -1.0, 1.0),
            -config.maxActuatorOutput(),
            config.maxActuatorOutput());

    leftJaw.set(applyInversion(limitedOutput, config.leftJawInverted()));
    rightJaw.set(applyInversion(limitedOutput, config.rightJawInverted()));
  }

  private static double applyInversion(double output, boolean inverted) {
    return inverted ? -output : output;
  }
}
