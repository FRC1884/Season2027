package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.math.geometry.Rotation2d;
import java.lang.reflect.Field;
import java.util.Set;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.runtime.RuntimeModeProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;

class SwerveLoggingTest {
  private static final String MODULE = "Swerve/Module0/";
  private RuntimeModeProfile originalProfile;
  private Object originalOutputTable;
  private boolean originalRunning;
  private Field outputTableField;
  private Field runningField;
  private SwerveSubsystem subsystem;
  private final FakeModule module = new FakeModule();
  private double now;
  private double radius = 0.05;

  @BeforeEach
  void setUp() throws Exception {
    originalProfile = RuntimeModeManager.getActiveProfile();
    setMode(false);
    outputTableField = Logger.class.getDeclaredField("outputTable");
    runningField = Logger.class.getDeclaredField("running");
    outputTableField.setAccessible(true);
    runningField.setAccessible(true);
    originalOutputTable = outputTableField.get(null);
    originalRunning = runningField.getBoolean(null);
    // Capture recordOutput directly on this test thread, without starting any logger receivers.
    runningField.setBoolean(null, false);
    subsystem =
        new SwerveSubsystem(
            new FakeGyro(),
            module,
            new FakeModule(),
            new FakeModule(),
            new FakeModule(),
            () -> now,
            () -> radius);
  }

  @AfterEach
  void tearDown() throws Exception {
    runningField.setBoolean(null, false);
    if (subsystem != null) subsystem.close();
    outputTableField.set(null, originalOutputTable);
    runningField.setBoolean(null, originalRunning);
    RuntimeModeManager.setActiveProfile(originalProfile);
  }

  @Test
  void competitionDerivedOutputsAreTenHzAndSensorSamplesRemainEveryCycle() throws Exception {
    int derivedPublications = 0;
    for (int cycle = 0; cycle < 25; cycle++) {
      module.velocity = cycle + 1.0;
      LogTable table = cycle(cycle * 0.02);
      boolean publish = cycle % 5 == 0;
      assertEquals(publish, table.get(MODULE + "ActualSpeedMps") != null);
      assertEquals(publish, table.get("Swerve/Debug/MeasuredSpeedMps") != null);
      assertEquals(publish, table.get("Swerve/FieldVelocityMps") != null);
      if (publish) {
        derivedPublications++;
        assertEquals(module.velocity * radius, table.get(MODULE + "ActualSpeedMps", -1.0), 1e-12);
      }
      assertEquals(module.velocity, table.get(MODULE + "Inputs/DriveVelocityRadPerSec", -1.0));
      assertArrayEquals(
          new double[] {now}, table.get(MODULE + "Inputs/OdometryTimestamps", new double[0]));
      assertArrayEquals(
          new double[] {now * 2.0},
          table.get(MODULE + "Inputs/OdometryDrivePositionsRad", new double[0]));
      assertArrayEquals(
          new double[] {now}, table.get("Swerve/Gyro/OdometryYawTimestamps", new double[0]));
      assertNotNull(table.get("Swerve/Gyro/OdometryYawPositions"));
      assertNotNull(table.get("SwerveStates/Measured"));
      assertNotNull(table.get("SwerveChassisSpeeds/Measured"));
    }
    assertEquals(5, derivedPublications);
  }

  @Test
  void calibrationRecordsInitiallyAndOnChangeOutsideTelemetryTick() throws Exception {
    LogTable initial = cycle(0.0);
    assertEquals(radius, initial.get("Swerve/Calibration/WheelRadiusMeters", -1.0));
    assertEquals(0.0, initial.get(MODULE + "ZeroTrimRotations", -1.0));
    assertEquals(0.0, initial.get("Swerve/Calibration/Module0/ZeroTrimRotations", -1.0));
    LogTable unchanged = cycle(0.02);
    assertNull(unchanged.get("Swerve/Calibration/WheelRadiusMeters"));
    assertNull(unchanged.get(MODULE + "ZeroTrimRotations"));
    radius = 0.06;
    module.trim = 0.125;
    LogTable changed = cycle(0.04);
    assertNull(changed.get(MODULE + "ActualSpeedMps"));
    assertEquals(radius, changed.get("Swerve/Calibration/WheelRadiusMeters", -1.0));
    assertEquals(module.trim, changed.get(MODULE + "ZeroTrimRotations", -1.0));
    assertEquals(module.trim, changed.get("Swerve/Calibration/Module0/ZeroTrimRotations", -1.0));
    assertNull(cycle(0.06).get(MODULE + "ZeroTrimRotations"));
  }

  @Test
  void angleFaultAndConnectionTransitionsAreImmediateBetweenTelemetryTicks() throws Exception {
    cycle(0.0);
    module.angle = Rotation2d.fromDegrees(90.0);
    module.connected = false;
    LogTable fault = cycle(0.02);
    assertNull(fault.get(MODULE + "ActualSpeedMps"));
    assertTrue(fault.get(MODULE + "AngleJumpDetected", false));
    assertEquals(1, fault.get(MODULE + "AngleJumpCount", -1));
    assertFalse(fault.get(MODULE + "Inputs/DriveConnected", true));
    module.connected = true;
    LogTable recovered = cycle(0.04);
    assertFalse(recovered.get(MODULE + "AngleJumpDetected", true));
    assertTrue(recovered.get(MODULE + "Inputs/DriveConnected", false));
  }

  @Test
  void debugTransitionsAndCharacterizationRetainFullRateDerivedOutputs() throws Exception {
    cycle(0.0);
    assertNull(cycle(0.02).get(MODULE + "ActualSpeedMps"));
    setMode(true);
    assertNotNull(cycle(0.04).get(MODULE + "ActualSpeedMps"));
    assertNotNull(cycle(0.06).get(MODULE + "ActualSpeedMps"));
    setMode(false);
    assertNotNull(cycle(0.08).get(MODULE + "ActualSpeedMps"));
    assertNull(cycle(0.10).get(MODULE + "ActualSpeedMps"));
    for (int i = 0; i < 5; i++) {
      subsystem.runCharacterization(0.0);
      LogTable characterization = cycle(0.12 + i * 0.02);
      assertNotNull(characterization.get(MODULE + "ActualSpeedMps"));
      assertNotNull(characterization.get(MODULE + "Inputs/DriveAppliedVolts"));
      assertNotNull(characterization.get(MODULE + "Inputs/DrivePositionRad"));
      assertNotNull(characterization.get(MODULE + "Inputs/DriveVelocityRadPerSec"));
    }
  }

  @Test
  void activeSysIdPhasesPublishEveryCycleAndCompletionIsImmediate() throws Exception {
    cycle(0.0);
    assertNull(cycle(0.02).get(MODULE + "ActualSpeedMps"));
    // Use the same phase transition method as the command; avoid log-file rollover in this test.
    var phase =
        SwerveSubsystem.class.getDeclaredMethod("setDriveSysIdPhase", String.class, boolean.class);
    phase.setAccessible(true);
    phase.invoke(subsystem, "SINGLE_DYN_FWD", true);
    for (int i = 0; i < 4; i++) {
      LogTable active = cycle(0.04 + i * 0.02);
      assertTrue(active.get("Swerve/SysId/DriveActive", false));
      assertEquals("SINGLE_DYN_FWD", active.get("Swerve/SysId/DrivePhase", ""));
      assertNotNull(active.get(MODULE + "ActualSpeedMps"));
      assertNotNull(active.get(MODULE + "Inputs/DriveAppliedVolts"));
    }
    phase.invoke(subsystem, "DONE", false);
    LogTable completed = cycle(0.12);
    assertFalse(completed.get("Swerve/SysId/DriveActive", true));
    assertEquals("DONE", completed.get("Swerve/SysId/DrivePhase", ""));
  }

  private void setMode(boolean debug) {
    RuntimeModeManager.setActiveProfile(
        new RuntimeModeProfile(
            debug ? GlobalConstants.LoggingMode.DEBUG : GlobalConstants.LoggingMode.COMP,
            false,
            Set.of(),
            null,
            null));
  }

  private LogTable cycle(double timestamp) throws Exception {
    now = timestamp;
    LogTable table = new LogTable((long) (timestamp * 1e6));
    outputTableField.set(null, table);
    runningField.setBoolean(null, true);
    try {
      subsystem.periodic();
    } finally {
      runningField.setBoolean(null, false);
    }
    return table;
  }

  private static class FakeModule implements ModuleIO {
    double velocity = 1.0;
    double trim;
    boolean connected = true;
    Rotation2d angle = Rotation2d.kZero;

    @Override
    public void updateInputs(ModuleIOInputs inputs, double timestamp) {
      inputs.driveConnected = connected;
      inputs.turnConnected = connected;
      inputs.driveVelocityRadPerSec = velocity;
      inputs.turnPosition = angle;
      inputs.turnAbsolutePosition = angle;
      inputs.turnZeroTrimRotations = trim;
      inputs.odometryTimestamps = new double[] {timestamp};
      inputs.odometryDrivePositionsRad = new double[] {timestamp * 2.0};
      inputs.odometryTurnPositions = new Rotation2d[] {angle};
      inputs.odometryTurnPositionsRotations = new double[] {angle.getRotations()};
    }
  }

  private static class FakeGyro implements GyroIO {
    @Override
    public void updateInputs(GyroIOInputs inputs, double timestamp) {
      inputs.connected = true;
      inputs.odometryYawTimestamps = new double[] {timestamp};
      inputs.odometryYawPositions = new Rotation2d[] {Rotation2d.kZero};
    }
  }
}
