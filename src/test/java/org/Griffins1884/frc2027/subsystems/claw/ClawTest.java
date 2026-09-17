package org.Griffins1884.frc2027.subsystems.claw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import java.util.OptionalDouble;
import org.Griffins1884.frc2027.safety.SafetyConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClawTest {
  private final CommandScheduler scheduler = CommandScheduler.getInstance();
  private final FakeClawIO io = new FakeClawIO();

  @BeforeEach
  void setUp() {
    assertTrue(HAL.initialize(500, 0));
    DriverStationSim.resetData();
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    scheduler.enable();
  }

  @AfterEach
  void tearDown() {
    scheduler.cancelAll();
    scheduler.unregisterAllSubsystems();
    DriverStationSim.resetData();
    DriverStationSim.notifyNewData();
  }

  @Test
  void defaultClawRemainsDisconnected() {
    Claw claw = new Claw();
    assertFalse(claw.isConfigured());
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    assertFalse(claw.isConfigured());
  }

  @Test
  void constructorClearsBothOutputs() {
    io.setRollerOutputs(0.2, 0.3);
    new Claw(io, OptionalDouble.empty());
    assertStopped();
  }

  @Test
  void missingSpeedCannotIntake() {
    Claw claw = new Claw(io, OptionalDouble.empty());
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    assertFalse(claw.isConfigured());
    assertStopped();
  }

  @Test
  void missingHardwareConfigurationCannotIntake() {
    io.configured = false;
    Claw claw = configuredClaw();
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    assertFalse(claw.isConfigured());
    assertStopped();
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {0.0, -0.1, 1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void invalidSpeedCannotIntake(double output) {
    Claw claw = new Claw(io, OptionalDouble.of(output));
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    assertFalse(claw.isConfigured());
    assertStopped();
  }

  @Test
  void scheduledIntakeRunsBothRollersAndCancellationStopsBoth() {
    Claw claw = configuredClaw();
    Command intake = claw.intakeCommand();
    assertTrue(intake.getRequirements().contains(claw));
    assertFalse(intake.runsWhenDisabled());
    scheduler.schedule(intake);
    scheduler.run();
    assertEquals(0.2, io.leftOutput);
    assertEquals(0.2, io.rightOutput);
    intake.cancel();
    assertStopped();
  }

  @Test
  void conflictingCommandInterruptsIntakeAndStopsBothRollers() {
    Claw claw = configuredClaw();
    Command intake = claw.intakeCommand();
    scheduler.schedule(intake);
    scheduler.run();
    scheduler.schedule(claw.runOnce(() -> {}));
    assertFalse(intake.isScheduled());
    assertStopped();
  }

  @Test
  void disablingStopsBothRollersAndCancelsIntake() {
    Claw claw = configuredClaw();
    Command intake = claw.intakeCommand();
    scheduler.schedule(intake);
    scheduler.run();
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    scheduler.run();
    assertFalse(intake.isScheduled());
    assertStopped();
    scheduler.schedule(intake);
    scheduler.run();
    assertFalse(intake.isScheduled());
    assertStopped();
  }

  @Test
  void losingHardwareConfigurationStopsActiveIntake() {
    Claw claw = configuredClaw();
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    io.configured = false;
    scheduler.run();
    assertStopped();
  }

  @Test
  void explicitStopClearsBothOutputs() {
    Claw claw = configuredClaw();
    scheduler.schedule(claw.intakeCommand());
    scheduler.run();
    claw.stop();
    assertStopped();
  }

  @Test
  void outputLimitIsAcceptedButLargerOutputIsRejected() {
    Claw atLimit = new Claw(io, OptionalDouble.of(SafetyConstants.DEFAULT_MOTOR_OUTPUT_LIMIT));
    assertTrue(atLimit.isConfigured());
    Claw aboveLimit =
        new Claw(io, OptionalDouble.of(Math.nextUp(SafetyConstants.DEFAULT_MOTOR_OUTPUT_LIMIT)));
    assertFalse(aboveLimit.isConfigured());
  }

  private Claw configuredClaw() {
    // This output is test data only; the production intake speed remains unset.
    return new Claw(io, OptionalDouble.of(0.2));
  }

  private void assertStopped() {
    assertEquals(0.0, io.leftOutput);
    assertEquals(0.0, io.rightOutput);
  }

  private static final class FakeClawIO implements ClawIO {
    private boolean configured = true;
    private double leftOutput;
    private double rightOutput;

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public void setRollerOutputs(double leftOutput, double rightOutput) {
      this.leftOutput = leftOutput;
      this.rightOutput = rightOutput;
    }
  }
}
