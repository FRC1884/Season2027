package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import java.util.Set;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.runtime.RuntimeModeProfile;
import org.Griffins1884.frc2027.simulation.GenericSimArena;
import org.ironmaple.simulation.SimulatedArena;
import org.ironmaple.simulation.drivesims.SwerveDriveSimulation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SwerveSimulationSnapshotTest {
  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void mapleSubstepsProduceAlignedWheelAndGyroSnapshots() {
    GenericSimArena.install();
    SimulatedArena previousArena = SimulatedArena.getInstance();
    SimulatedArena.overrideInstance(new TestArena());
    var simulation = new SwerveDriveSimulation(SwerveConstants.MAPLE_SIM_CONFIG, Pose2d.kZero);
    SimulatedArena.getInstance().addDriveTrainSimulation(simulation);
    ModuleIOSim[] modules = new ModuleIOSim[4];
    for (int i = 0; i < 4; i++) modules[i] = new ModuleIOSim(simulation.getModules()[i]);
    final double[] now = {0.02};
    var drive =
        new SwerveSubsystem(
            new GyroIOSim(simulation.getGyroSimulation()),
            modules[0],
            modules[1],
            modules[2],
            modules[3],
            () -> now[0],
            SwerveConstants::getWheelRadiusMeters);
    try {
      for (int cycle = 0; cycle < 20; cycle++) {
        SimulatedArena.getInstance().simulationPeriodic();
        now[0] += 0.02;
        drive.periodic();
        assertEquals(0, drive.invalidSnapshotCount());
        assertTrue(Double.isFinite(drive.getPose().getX()));
        assertTrue(Double.isFinite(drive.getRobotRelativeSpeeds().vxMetersPerSecond));
      }
    } finally {
      drive.close();
      SimulatedArena.getInstance().shutDown();
      SimulatedArena.overrideInstance(previousArena);
    }
  }

  @Test
  void simulatedFeedforwardChangesWaitUntilDisabledTuning() {
    GenericSimArena.install();
    var simulation = new SwerveDriveSimulation(SwerveConstants.MAPLE_SIM_CONFIG, Pose2d.kZero);
    var previous = RuntimeModeManager.getActiveProfile();
    String key = "/SmartDashboard/TunableNumbers/Swerve/DriveMotor/Simbot/kV";
    var entry = NetworkTableInstance.getDefault().getEntry(key);
    double original = SwerveConstants.DRIVE_MOTOR_GAINS.kV().get();
    try {
      RuntimeModeManager.setActiveProfile(
          new RuntimeModeProfile(GlobalConstants.LoggingMode.DEBUG, true, Set.of(), null, null));
      entry.setDouble(original);
      // Create registered network input now; later periodic calls load each edit.
      SwerveConstants.DRIVE_MOTOR_GAINS.kV().get();
      ModuleIOSim io = new ModuleIOSim(simulation.getModules()[0]);
      ModuleIO.ModuleIOInputs inputs = new ModuleIO.ModuleIOInputs();
      DriverStationSim.setEnabled(true);
      DriverStationSim.notifyNewData();
      io.setDriveVelocity(2.0);
      io.updateInputs(inputs, 0.02);
      double before = inputs.driveAppliedVolts;
      entry.setDouble(original + 0.5);
      refreshDashboardInput(SwerveConstants.DRIVE_MOTOR_GAINS.kV());
      io.setDriveVelocity(2.0);
      io.updateInputs(inputs, 0.04);
      assertEquals(before, inputs.driveAppliedVolts, 1e-9);
      DriverStationSim.setEnabled(false);
      DriverStationSim.notifyNewData();
      io.updateInputs(inputs, 0.06);
      io.setDriveVelocity(2.0);
      io.updateInputs(inputs, 0.08);
      assertEquals(before + 1.0, inputs.driveAppliedVolts, 1e-9);
    } finally {
      entry.setDouble(original);
      refreshDashboardInput(SwerveConstants.DRIVE_MOTOR_GAINS.kV());
      RuntimeModeManager.setActiveProfile(previous);
      DriverStationSim.setEnabled(false);
      DriverStationSim.notifyNewData();
    }
  }

  private static class TestArena extends SimulatedArena {
    TestArena() {
      super(new FieldMap() {});
    }

    @Override
    public void placeGamePiecesOnField() {}
  }

  private static void refreshDashboardInput(
      org.Griffins1884.frc2027.util.LoggedTunableNumber number) {
    try {
      var field = number.getClass().getDeclaredField("dashboardNumber");
      field.setAccessible(true);
      ((org.littletonrobotics.junction.networktables.LoggedNetworkNumber) field.get(number))
          .periodic();
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }
}
