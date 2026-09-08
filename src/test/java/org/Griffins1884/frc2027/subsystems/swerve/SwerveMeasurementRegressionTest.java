package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SwerveMeasurementRegressionTest {
  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void straightTracePreservesPoseAndMeasuredSpeeds() {
    FakeModule[] ios = {new FakeModule(), new FakeModule(), new FakeModule(), new FakeModule()};
    SwerveSubsystem drive = new SwerveSubsystem(new GyroIO() {}, ios[0], ios[1], ios[2], ios[3]);
    double radius = SwerveConstants.getWheelRadiusMeters();
    try {
      for (int cycle = 1; cycle <= 10; cycle++) {
        for (FakeModule io : ios) {
          io.timestamp = cycle * 0.02;
          io.position = cycle * 0.04;
          io.velocity = 2.0;
        }
        drive.periodic();
        assertEquals(cycle * 0.04 * radius, drive.getPose().getX(), 1e-9);
        assertEquals(0.0, drive.getPose().getY(), 1e-9);
        assertEquals(0.0, drive.getPose().getRotation().getRadians(), 1e-9);
        ChassisSpeeds speeds = drive.getRobotRelativeSpeeds();
        assertEquals(2.0 * radius, speeds.vxMetersPerSecond, 1e-9);
        assertEquals(0.0, speeds.vyMetersPerSecond, 1e-9);
        assertEquals(0.0, speeds.omegaRadiansPerSecond, 1e-9);
      }
    } finally {
      CommandScheduler.getInstance().unregisterSubsystem(drive);
    }
  }

  @Test
  void resetUsesCurrentWheelPositionsAndKeepsSubsequentDeltas() {
    FakeModule io = new FakeModule();
    SwerveSubsystem drive = new SwerveSubsystem(new GyroIO() {}, io, io, io, io);
    double radius = SwerveConstants.getWheelRadiusMeters();
    try {
      io.position = 1.0;
      drive.periodic();
      drive.resetOdometry(new Pose2d(2.0, 3.0, Rotation2d.kZero));
      io.position = 1.5;
      io.timestamp += 0.02;
      drive.periodic();
      assertEquals(2.0 + 0.5 * radius, drive.getPose().getX(), 1e-9);
      assertEquals(3.0, drive.getPose().getY(), 1e-9);
    } finally {
      CommandScheduler.getInstance().unregisterSubsystem(drive);
    }
  }

  @Test
  void characterizationAndStopKeepImmediateOutputs() {
    FakeModule io = new FakeModule();
    Module module = new Module(io, 0);
    module.periodic();
    module.runCharacterization(3.25);
    assertEquals(3.25, io.driveOutput);
    assertEquals(Rotation2d.kZero, io.turnTarget);
    module.runTurnCharacterization(-2.0);
    assertEquals(0.0, io.driveOutput);
    assertEquals(-2.0, io.turnOutput);
    module.stop();
    assertEquals(0.0, io.driveOutput);
    assertEquals(0.0, io.turnOutput);
  }

  static class FakeModule implements ModuleIO {
    double timestamp = 0.02;
    double position;
    double velocity;
    double driveOutput;
    double turnOutput;
    Rotation2d turnTarget;

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
      inputs.driveConnected = true;
      inputs.turnConnected = true;
      inputs.drivePositionRad = position;
      inputs.driveVelocityRadPerSec = velocity;
      inputs.turnPosition = Rotation2d.kZero;
      inputs.odometryTimestamps = new double[] {timestamp};
      inputs.odometryDrivePositionsRad = new double[] {position};
      inputs.odometryTurnPositions = new Rotation2d[] {Rotation2d.kZero};
    }

    @Override
    public void setDriveOpenLoop(double output) {
      driveOutput = output;
    }

    @Override
    public void setTurnOpenLoop(double output) {
      turnOutput = output;
    }

    @Override
    public void setTurnPosition(Rotation2d target) {
      turnTarget = target;
    }
  }
}
