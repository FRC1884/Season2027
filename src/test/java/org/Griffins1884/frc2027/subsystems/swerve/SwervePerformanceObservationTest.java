package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Instrumentation must observe identical control results and count accepted samples only. */
class SwervePerformanceObservationTest {
  @BeforeAll
  static void initialize() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void observationPreservesControlAndCountsActualSamples() {
    Fake[] plainIO = modules();
    Fake[] observedIO = modules();
    var plain = drive(plainIO);
    var observed = drive(observedIO);
    observed.setPerformanceObservationEnabled(true);
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    try {
      for (int cycle = 1; cycle <= 20; cycle++) {
        for (int i = 0; i < 4; i++) {
          plainIO[i].cycle = cycle;
          observedIO[i].cycle = cycle;
        }
        plain.periodic();
        observed.periodic();
        var requested = new ChassisSpeeds(Math.sin(cycle * .3), Math.cos(cycle * .2), .2);
        plain.runVelocity(requested);
        observed.runVelocity(requested);
        assertEquals(plain.getPose().getX(), observed.getPose().getX(), 1e-12);
        assertEquals(plain.getPose().getY(), observed.getPose().getY(), 1e-12);
        assertEquals(
            plain.getRobotRelativeSpeeds().vxMetersPerSecond,
            observed.getRobotRelativeSpeeds().vxMetersPerSecond,
            1e-12);
        for (int i = 0; i < 4; i++) {
          assertEquals(plainIO[i].velocity, observedIO[i].velocity, 1e-12);
          assertEquals(plainIO[i].angle, observedIO[i].angle, 1e-12);
        }
      }
      var snapshot = observed.getPerformanceSnapshot();
      assertEquals(20, snapshot.acquiredSamples());
      assertEquals(20, snapshot.consumedSamples());
      assertEquals(20, snapshot.driveRequests());
      assertEquals(20, snapshot.appliedDriveRequests());
      assertEquals(0, plain.getPerformanceSnapshot().consumedSamples());
      observedIO[1].malformed = true;
      observed.periodic();
      assertEquals(21, observed.getPerformanceSnapshot().acquiredSamples());
      assertEquals(20, observed.getPerformanceSnapshot().consumedSamples());
      assertEquals(1, observed.getPerformanceSnapshot().invalidSnapshots());
      observed.setPerformanceObservationEnabled(false);
      observed.periodic();
      assertEquals(21, observed.getPerformanceSnapshot().acquiredSamples());
    } finally {
      DriverStationSim.setEnabled(false);
      DriverStationSim.notifyNewData();
      plain.close();
      observed.close();
    }
  }

  private static Fake[] modules() {
    return new Fake[] {new Fake(), new Fake(), new Fake(), new Fake()};
  }

  private static SwerveSubsystem drive(Fake[] io) {
    return new SwerveSubsystem(new GyroIO() {}, io[0], io[1], io[2], io[3], () -> .4, () -> .05);
  }

  private static final class Fake implements ModuleIO {
    int cycle;
    boolean malformed;
    double velocity, angle;

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
      inputs.drivePositionRad = cycle * .1;
      inputs.driveVelocityRadPerSec = 5;
      inputs.turnPosition = Rotation2d.kZero;
      inputs.odometryTimestamps = new double[] {cycle * .02 + (malformed ? .001 : 0)};
      inputs.odometryDrivePositionsRad = new double[] {cycle * .1};
      inputs.odometryTurnPositions = new Rotation2d[] {Rotation2d.kZero};
    }

    @Override
    public void setDriveVelocity(double velocityRadPerSec) {
      velocity = velocityRadPerSec;
    }

    @Override
    public void setTurnPosition(Rotation2d rotation) {
      angle = rotation.getRadians();
    }
  }
}
