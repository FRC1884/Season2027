package org.Griffins1884.frc2027.subsystems.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import java.util.OptionalDouble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElevatorTest {
  private final CommandScheduler scheduler = CommandScheduler.getInstance();
  private final FakeElevatorIO io = new FakeElevatorIO();

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
  void defaultElevatorAndHardwareRemainDisconnected() {
    Elevator elevator = new Elevator();
    scheduler.schedule(elevator.goToPresetCommand(measuredPreset()));
    scheduler.run();
    assertFalse(elevator.isReady());
    ElevatorIO hardware = new ElevatorIOKrakenX60();
    hardware.setHeightMeters(0.5);
    hardware.stop();
    assertFalse(hardware.isReady());
    assertFalse(hardware.isHeightAllowed(0.5));
  }

  @Test
  void constructorStopsBothMotors() {
    io.setHeightMeters(0.5);
    new Elevator(io);
    assertStopped();
  }

  @Test
  void unsetPresetStopsAnExistingRequestInsteadOfRequestingZero() {
    Elevator elevator = new Elevator(io);
    scheduler.schedule(elevator.goToPresetCommand(measuredPreset()));
    scheduler.run();
    assertTarget(0.5);
    int previousRequests = io.heightRequests;
    Elevator.Preset placeholder = new Elevator.Preset("loading");
    assertTrue(placeholder.heightMeters().isEmpty());
    scheduler.schedule(elevator.goToPresetCommand(placeholder));
    scheduler.run();
    assertEquals(previousRequests, io.heightRequests);
    assertStopped();
  }

  @Test
  void missingConfigurationOrReferencePreventsPositionRequests() {
    io.ready = false;
    Elevator elevator = new Elevator(io);
    scheduler.schedule(elevator.goToPresetCommand(measuredPreset()));
    scheduler.run();
    assertEquals(0, io.heightRequests);
    assertStopped();
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1})
  void invalidOrOutOfTravelHeightCannotMove(double heightMeters) {
    Elevator elevator = new Elevator(io);
    scheduler.schedule(
        elevator.goToPresetCommand(new Elevator.Preset("test", OptionalDouble.of(heightMeters))));
    scheduler.run();
    assertEquals(0, io.heightRequests);
    assertStopped();
  }

  @Test
  void presetRequestsCommonHeightUntilCancelled() {
    Elevator elevator = new Elevator(io);
    Command command = elevator.goToPresetCommand(measuredPreset());
    assertTrue(command.getRequirements().contains(elevator));
    assertFalse(command.runsWhenDisabled());
    scheduler.schedule(command);
    scheduler.run();
    scheduler.run();
    assertTrue(command.isScheduled());
    assertTarget(0.5);
    command.cancel();
    assertStopped();
  }

  @Test
  void conflictingCommandStopsBothMotors() {
    Elevator elevator = new Elevator(io);
    Command command = elevator.goToPresetCommand(measuredPreset());
    scheduler.schedule(command);
    scheduler.run();
    assertTarget(0.5);
    scheduler.schedule(elevator.runOnce(() -> {}));
    assertFalse(command.isScheduled());
    assertStopped();
  }

  @Test
  void disablingCancelsPositionControlAndPreventsScheduling() {
    Elevator elevator = new Elevator(io);
    Command command = elevator.goToPresetCommand(measuredPreset());
    scheduler.schedule(command);
    scheduler.run();
    assertTarget(0.5);
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    scheduler.run();
    assertFalse(command.isScheduled());
    assertStopped();
    scheduler.schedule(command);
    scheduler.run();
    assertFalse(command.isScheduled());
    assertStopped();
  }

  @Test
  void losingReadinessStopsBothMotorsWithoutAnotherHeightRequest() {
    Elevator elevator = new Elevator(io);
    scheduler.schedule(elevator.goToPresetCommand(measuredPreset()));
    scheduler.run();
    assertTarget(0.5);
    int previousRequests = io.heightRequests;
    io.ready = false;
    scheduler.run();
    assertEquals(previousRequests, io.heightRequests);
    assertStopped();
  }

  @Test
  void newlyBlockedInterlockStopsBothMotors() {
    Elevator elevator = new Elevator(io);
    scheduler.schedule(elevator.goToPresetCommand(measuredPreset()));
    scheduler.run();
    assertTarget(0.5);
    int previousRequests = io.heightRequests;
    io.interlockAllowsMotion = false;
    scheduler.run();
    assertEquals(previousRequests, io.heightRequests);
    assertStopped();
  }

  private static Elevator.Preset measuredPreset() {
    // Test fixture only: no production preset heights or travel limits have been chosen.
    return new Elevator.Preset("test", OptionalDouble.of(0.5));
  }

  private void assertTarget(double heightMeters) {
    assertEquals(OptionalDouble.of(heightMeters), io.firstMotorTarget);
    assertEquals(OptionalDouble.of(heightMeters), io.secondMotorTarget);
  }

  private void assertStopped() {
    assertTrue(io.firstMotorTarget.isEmpty());
    assertTrue(io.secondMotorTarget.isEmpty());
  }

  private static final class FakeElevatorIO implements ElevatorIO {
    private boolean ready = true;
    private boolean interlockAllowsMotion = true;
    private int heightRequests;
    private OptionalDouble firstMotorTarget = OptionalDouble.empty();
    private OptionalDouble secondMotorTarget = OptionalDouble.empty();

    @Override
    public boolean isReady() {
      return ready;
    }

    @Override
    public boolean isHeightAllowed(double heightMeters) {
      // Test fixture limits only, not physical robot values.
      return interlockAllowsMotion && heightMeters >= 0.0 && heightMeters <= 1.0;
    }

    @Override
    public void setHeightMeters(double heightMeters) {
      heightRequests++;
      firstMotorTarget = OptionalDouble.of(heightMeters);
      secondMotorTarget = OptionalDouble.of(heightMeters);
    }

    @Override
    public void stop() {
      firstMotorTarget = OptionalDouble.empty();
      secondMotorTarget = OptionalDouble.empty();
    }
  }
}
