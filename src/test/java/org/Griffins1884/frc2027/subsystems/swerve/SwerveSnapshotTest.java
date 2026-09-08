package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SwerveSnapshotTest {
  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void oneFreshKinematicsAndRadiusReadServeRepeatedConsumers() {
    AtomicInteger reads = new AtomicInteger();
    SampleModule[] ios = modules();
    SwerveSubsystem drive =
        new SwerveSubsystem(
            new GyroIO() {},
            ios[0],
            ios[1],
            ios[2],
            ios[3],
            () -> 1.0,
            () -> {
              reads.incrementAndGet();
              return 0.05;
            });
    try {
      ios[0].velocity = 4.0;
      drive.periodic();
      long count = drive.measuredKinematicsCount();
      for (int i = 0; i < 10; i++) {
        var speeds = drive.getRobotRelativeSpeeds();
        assertEquals(0.05, speeds.vxMetersPerSecond, 1e-9);
        speeds.vxMetersPerSecond = 999.0; // A consumer cannot corrupt the cached measurement.
      }
      assertEquals(count, drive.measuredKinematicsCount());
      assertEquals(1, reads.get());
      ios[0].velocity = 8.0;
      drive.periodic();
      assertEquals(0.10, drive.getRobotRelativeSpeeds().vxMetersPerSecond, 1e-9);
      assertEquals(count + 1, drive.measuredKinematicsCount());
      assertEquals(2, reads.get());
    } finally {
      drive.close();
    }
  }

  @Test
  void calibrationWritesRefreshImmediateConsumersAndResetMeasurements() {
    SampleModule[] ios = modules();
    for (var io : ios) io.velocity = 2.0;
    SwerveSubsystem drive = drive(new GyroIO() {}, ios);
    try {
      SwerveCalibration.setWheelRadiusMeters(0.05);
      drive.periodic();
      assertEquals(0.10, drive.getRobotRelativeSpeeds().vxMetersPerSecond, 1e-9);
      SwerveCalibration.setWheelRadiusMeters(0.06);
      assertEquals(0.12, drive.getRobotRelativeSpeeds().vxMetersPerSecond, 1e-9);
      SwerveCalibration.clearWheelRadiusMeters();
      drive.resetOdometry(Pose2d.kZero);
      assertEquals(
          2.0 * SwerveConstants.getWheelRadiusMeters(),
          drive.getRobotRelativeSpeeds().vxMetersPerSecond,
          1e-9);
    } finally {
      SwerveCalibration.clearWheelRadiusMeters();
      drive.close();
    }
  }

  @Test
  void malformedAndMisalignedSamplesAreRejectedAsWholeBatches() {
    SampleModule[] ios = modules();
    SwerveSubsystem drive = drive(new GyroIO() {}, ios);
    try {
      ios[1].timestamps = new double[] {0.03};
      drive.periodic();
      assertEquals(1, drive.invalidSnapshotCount());
      assertEquals(Pose2d.kZero, drive.getPose());
      ios[1].timestamps = new double[] {0.02};
      ios[2].positions = new double[] {};
      drive.periodic();
      assertEquals(2, drive.invalidSnapshotCount());
      for (var io : ios) {
        io.timestamps = new double[] {};
        io.positions = new double[] {};
        io.angles = new Rotation2d[] {};
      }
      drive.periodic();
      assertEquals(2, drive.invalidSnapshotCount());
    } finally {
      drive.close();
    }
  }

  @Test
  void connectedGyroTimestampsMustMatchWheelSamples() {
    SampleModule[] ios = modules();
    SampleGyro gyro = new SampleGyro();
    gyro.timestamps = new double[] {0.03};
    SwerveSubsystem drive = drive(gyro, ios);
    try {
      drive.periodic();
      assertEquals(1, drive.invalidSnapshotCount());
      gyro.timestamps = new double[] {};
      gyro.positions = new Rotation2d[] {};
      drive.periodic(); // Preserve existing empty-gyro behavior: hold the previous heading.
      assertEquals(1, drive.invalidSnapshotCount());
      assertEquals(0.0, drive.getRotation().getRadians());
    } finally {
      drive.close();
    }
  }

  @Test
  void connectedSamplesMaintainHistoryForImmediateDisconnectedFallback() {
    SampleModule[] ios = modules();
    SampleGyro gyro = new SampleGyro();
    SwerveSubsystem drive = drive(gyro, ios);
    double radius = SwerveConstants.getWheelRadiusMeters();
    try {
      for (int cycle = 0; cycle < 4; cycle++) {
        double heading = cycle * 0.1;
        for (int m = 0; m < 4; m++) {
          var translation = SwerveConstants.MODULE_TRANSLATIONS[m];
          ios[m].positions = new double[] {translation.getNorm() * heading / radius};
          ios[m].angles =
              new Rotation2d[] {translation.getAngle().plus(Rotation2d.fromDegrees(90))};
          ios[m].timestamps = new double[] {(cycle + 1) * 0.02};
        }
        gyro.connected = cycle != 2;
        gyro.positions = new Rotation2d[] {Rotation2d.fromRadians(heading)};
        gyro.timestamps = ios[0].timestamps;
        drive.periodic();
        assertEquals(heading, drive.getRawGyroRotation().getRadians(), 1e-9);
        assertEquals(0.0, drive.getPose().getX(), 1e-9);
        assertEquals(0.0, drive.getPose().getY(), 1e-9);
      }
    } finally {
      drive.close();
    }
  }

  @Test
  void concurrentResetWaitsForSnapshotThenDiscardsQueuedPreResetSamples() throws Exception {
    SampleModule[] ios = modules();
    CountDownLatch capturing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ios[0].captureHook =
        () -> {
          capturing.countDown();
          await(release);
        };
    for (var io : ios) io.positions = new double[] {1.0};
    SwerveSubsystem drive = drive(new GyroIO() {}, ios);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> periodic = executor.submit(drive::periodic);
      assertTrue(capturing.await(5, TimeUnit.SECONDS));
      CountDownLatch resetting = new CountDownLatch(1);
      Pose2d target = new Pose2d(3.0, 2.0, Rotation2d.kZero);
      Future<?> reset =
          executor.submit(
              () -> {
                resetting.countDown();
                drive.resetOdometry(target);
              });
      assertTrue(resetting.await(5, TimeUnit.SECONDS));
      release.countDown();
      periodic.get(5, TimeUnit.SECONDS);
      reset.get(5, TimeUnit.SECONDS);
      assertEquals(target, drive.getPose());
      for (var io : ios) assertEquals(1, io.clears);
      ios[0].captureHook = () -> {};
      for (var io : ios) {
        io.timestamps = new double[] {0.04};
        io.positions = new double[] {1.5};
      }
      drive.periodic();
      assertEquals(
          3.0 + 0.5 * SwerveConstants.getWheelRadiusMeters(), drive.getPose().getX(), 1e-9);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      drive.close();
    }
  }

  @Test
  void reentrantResetDuringCaptureInvalidatesTheCapturedGeneration() {
    SampleModule[] ios = modules();
    SwerveSubsystem drive = drive(new GyroIO() {}, ios);
    Pose2d target = new Pose2d(3, 2, Rotation2d.kZero);
    try {
      ios[0].captureHook = () -> drive.resetOdometry(target);
      drive.periodic();
      assertEquals(target, drive.getPose());
      assertEquals(1, drive.discardedSnapshotCount());
    } finally {
      drive.close();
    }
  }

  @Test
  void oneUnreadyModuleInhibitsEveryDriveAndCharacterizationOutput() {
    SampleModule[] ios = modules();
    SwerveSubsystem drive = drive(new GyroIO() {}, ios);
    try {
      ios[2].ready = false;
      drive.runCharacterization(3.0);
      drive.runTurnCharacterization(3.0);
      drive.runVelocity(new edu.wpi.first.math.kinematics.ChassisSpeeds(1, 0, 0));
      drive.stopWithX();
      for (var io : ios) {
        assertEquals(0.0, io.lastDriveOutput);
        assertEquals(0.0, io.lastTurnOutput);
      }
    } finally {
      drive.close();
    }
  }

  static SampleModule[] modules() {
    return new SampleModule[] {
      new SampleModule(), new SampleModule(), new SampleModule(), new SampleModule()
    };
  }

  static SwerveSubsystem drive(GyroIO gyro, SampleModule[] ios) {
    return new SwerveSubsystem(
        gyro, ios[0], ios[1], ios[2], ios[3], () -> 1.0, SwerveConstants::getWheelRadiusMeters);
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Missing synchronization signal");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  static class SampleModule implements ModuleIO {
    double[] timestamps = {0.02};
    double[] positions = {0.0};
    Rotation2d[] angles = {Rotation2d.kZero};
    double velocity;
    int clears;
    boolean ready = true;
    double lastDriveOutput = Double.NaN;
    double lastTurnOutput = Double.NaN;
    Runnable captureHook = () -> {};

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
      captureHook.run();
      inputs.driveConnected = true;
      inputs.turnConnected = true;
      inputs.drivePositionRad = positions.length == 0 ? 0.0 : positions[positions.length - 1];
      inputs.driveVelocityRadPerSec = velocity;
      inputs.turnPosition = angles.length == 0 ? Rotation2d.kZero : angles[angles.length - 1];
      inputs.odometryTimestamps = timestamps;
      inputs.odometryDrivePositionsRad = positions;
      inputs.odometryTurnPositions = angles;
    }

    @Override
    public void clearOdometrySamples() {
      clears++;
    }

    @Override
    public boolean isConfigurationReady() {
      return ready;
    }

    @Override
    public void setDriveOpenLoop(double output) {
      lastDriveOutput = output;
    }

    @Override
    public void setTurnOpenLoop(double output) {
      lastTurnOutput = output;
    }

    @Override
    public void setDriveVelocity(double velocity, double feedforward) {
      lastDriveOutput = velocity;
    }

    @Override
    public void setTurnPosition(Rotation2d rotation) {
      lastTurnOutput = rotation.getRadians();
    }
  }

  static class SampleGyro implements GyroIO {
    boolean connected = true;
    double[] timestamps = {0.02};
    Rotation2d[] positions = {Rotation2d.kZero};

    @Override
    public void updateInputs(GyroIOInputs inputs) {
      inputs.connected = connected;
      inputs.yawPosition =
          positions.length == 0 ? Rotation2d.kZero : positions[positions.length - 1];
      inputs.odometryYawTimestamps = timestamps;
      inputs.odometryYawPositions = positions;
    }
  }
}
