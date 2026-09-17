package org.Griffins1884.frc2027.subsystems.claw;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.Objects;
import java.util.OptionalDouble;
import org.Griffins1884.frc2027.safety.SafetyConstants;

/** Two Kraken X60 rollers that pull a game piece inward. Hardware is disconnected by default. */
public final class Claw extends SubsystemBase {
  private final ClawIO io;
  private final OptionalDouble intakeOutput;

  public Claw() {
    // TODO: Replace the empty speed with a reviewed positive duty cycle after hardware testing.
    this(new ClawIOKrakenX60(), OptionalDouble.empty());
  }

  /** Accepts hardware or test IO; an empty or invalid intake output keeps both rollers stopped. */
  public Claw(ClawIO io, OptionalDouble intakeOutput) {
    this.io = Objects.requireNonNull(io);
    this.intakeOutput = Objects.requireNonNull(intakeOutput);
    stop();
  }

  public boolean isConfigured() {
    if (!io.isConfigured() || intakeOutput.isEmpty()) {
      return false;
    }
    double output = intakeOutput.getAsDouble();
    return Double.isFinite(output)
        && output > 0.0
        && output <= SafetyConstants.DEFAULT_MOTOR_OUTPUT_LIMIT;
  }

  /**
   * Intakes while scheduled and stops both rollers when ended or interrupted.
   *
   * <p>TODO: Choose an operator button and bind it with whileTrue(intakeCommand()). Before
   * connecting this subsystem, instantiate it in RobotContainer and run CommandScheduler in
   * robotPeriodic.
   */
  public Command intakeCommand() {
    return runEnd(this::intake, this::stop).withName("Claw intake");
  }

  private void intake() {
    if (DriverStation.isDisabled() || !isConfigured()) {
      stop();
      return;
    }
    double output = intakeOutput.getAsDouble();
    io.setRollerOutputs(output, output);
  }

  public void stop() {
    io.setRollerOutputs(0.0, 0.0);
  }

  @Override
  public void periodic() {
    if (DriverStation.isDisabled() || !isConfigured()) {
      stop();
    }
  }
}
