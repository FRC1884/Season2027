package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Golden outputs captured by executing the unchanged integration base, not recomputed by the test.
 */
class SwerveControlTraceTest {
  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void commandOutputsAndMeasurementsMatchTheIntegrationBase() throws Exception {
    TraceModule[] io = {new TraceModule(), new TraceModule(), new TraceModule(), new TraceModule()};
    SwerveSubsystem drive = new SwerveSubsystem(new GyroIO() {}, io[0], io[1], io[2], io[3]);
    List<String> trace = new ArrayList<>();
    double[][] commands = {
      {1, 0, 0},
      {1, 0.5, 0.2},
      {0, 0, 0},
      {-0.5, 0, 0},
      {0, 0, 0.4},
      {0, 0, 0},
      {0.3, -0.4, -0.2},
      {0, 0, 0},
      {1, 1, 0},
      {0, 0, 0},
      {-1, -1, 0.1},
      {0, 0, 0}
    };
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
    try {
      for (int cycle = 0; cycle < commands.length; cycle++) {
        for (int m = 0; m < 4; m++) {
          io[m].cycle = cycle;
          io[m].index = m;
        }
        drive.periodic();
        double[] requested = commands[cycle];
        drive.runVelocity(new ChassisSpeeds(requested[0], requested[1], requested[2]));
        if (cycle == 7) drive.stopWithX();
        ChassisSpeeds measured = drive.getRobotRelativeSpeeds();
        for (int i = 0; i < 10; i++) drive.getRobotRelativeSpeeds();
        StringBuilder row =
            new StringBuilder(
                String.format(
                    Locale.ROOT,
                    "%.15g,%.15g,%.15g,%.15g,%.15g,%.15g",
                    drive.getPose().getX(),
                    drive.getPose().getY(),
                    drive.getPose().getRotation().getRadians(),
                    measured.vxMetersPerSecond,
                    measured.vyMetersPerSecond,
                    measured.omegaRadiansPerSecond));
        for (TraceModule module : io)
          row.append(
              String.format(
                  Locale.ROOT,
                  ",%.15g,%.15g,%.15g",
                  module.velocityRequest,
                  module.feedforward,
                  module.turnRequest));
        trace.add(row.toString());
      }
      String destination = System.getenv("SWERVE_TRACE_OUTPUT");
      if (destination != null) {
        Files.write(Path.of(destination), trace, StandardCharsets.UTF_8);

      } else {
        try (var input = getClass().getResourceAsStream("/swerve-control-trace.csv")) {
          assertNotNull(input, "Baseline fixture is required");
          String[] expected =
              new String(input.readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
          assertEquals(expected.length, trace.size());
          for (int row = 0; row < expected.length; row++) {
            String[] wanted = expected[row].split(",");
            String[] actual = trace.get(row).split(",");
            for (int column = 0; column < wanted.length; column++)
              assertEquals(
                  Double.parseDouble(wanted[column]),
                  Double.parseDouble(actual[column]),
                  1e-9,
                  "cycle " + row + " column " + column);
          }
        }
      }
    } finally {
      DriverStationSim.setEnabled(false);
      DriverStationSim.notifyNewData();
      CommandScheduler.getInstance().unregisterSubsystem(drive);
    }
  }

  @Test
  void repeatedMeasurementWorkloadCountsKinematicsSeparatelyFromCommandGeneration()
      throws Exception {
    TraceModule[] io = {new TraceModule(), new TraceModule(), new TraceModule(), new TraceModule()};
    SwerveSubsystem drive = new SwerveSubsystem(new GyroIO() {}, io[0], io[1], io[2], io[3]);
    CountingKinematics kinematics = new CountingKinematics();
    var field = SwerveSubsystem.class.getDeclaredField("kinematics");
    field.setAccessible(true);
    field.set(drive, kinematics);
    try {
      for (int cycle = 0; cycle < 12; cycle++) {
        for (int m = 0; m < 4; m++) {
          io[m].cycle = cycle;
          io[m].index = m;
        }
        drive.periodic();
        for (int consumer = 0; consumer < 11; consumer++) drive.getRobotRelativeSpeeds();
      }
      String destination = System.getenv("SWERVE_TRACE_OUTPUT");
      if (destination != null) {
        Files.writeString(
            Path.of(destination + ".counts"),
            "measured_kinematics="
                + kinematics.measuredCalls
                + "\ncycles=12\nexternal_consumers_per_cycle=11\n");
      } else {
        assertEquals(12, kinematics.measuredCalls);
      }
    } finally {
      CommandScheduler.getInstance().unregisterSubsystem(drive);
    }
  }

  private static class CountingKinematics extends SwerveDriveKinematics {
    int measuredCalls;

    CountingKinematics() {
      super(SwerveConstants.MODULE_TRANSLATIONS);
    }

    @Override
    public ChassisSpeeds toChassisSpeeds(SwerveModuleState... states) {
      measuredCalls++;
      return super.toChassisSpeeds(states);
    }
  }

  private static class TraceModule implements ModuleIO {
    int cycle;
    int index;
    double velocityRequest;
    double feedforward;
    double turnRequest;

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
      inputs.driveConnected = true;
      inputs.turnConnected = true;
      inputs.drivePositionRad = cycle * 0.02 * (index + 1);
      inputs.driveVelocityRadPerSec = index + 1;
      inputs.turnPosition = Rotation2d.fromRadians(0.03 * index);
      inputs.odometryTimestamps = new double[] {(cycle + 1) * 0.02};
      inputs.odometryDrivePositionsRad = new double[] {inputs.drivePositionRad};
      inputs.odometryTurnPositions = new Rotation2d[] {inputs.turnPosition};
    }

    @Override
    public void setDriveVelocity(double velocity, double ff) {
      velocityRequest = velocity;
      feedforward = ff;
    }

    @Override
    public void setTurnPosition(Rotation2d angle) {
      turnRequest = angle.getRadians();
    }

    @Override
    public void setTurnOpenLoop(double output) {
      turnRequest = output;
    }

    @Override
    public void setDriveOpenLoop(double output) {
      velocityRequest = output;
      feedforward = 0;
    }
  }
}
