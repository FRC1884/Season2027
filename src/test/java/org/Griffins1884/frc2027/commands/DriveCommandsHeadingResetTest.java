package org.Griffins1884.frc2027.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj2.command.Command;
import java.util.Optional;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIO;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIO;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;
import org.junit.jupiter.api.Test;

class DriveCommandsHeadingResetTest {
  private static final double EPSILON = 1e-6;

  @Test
  void resetsUsingAllianceResolvedWhenCommandRuns() {
    FakeGyro gyro = new FakeGyro();
    SwerveSubsystem drive = newSubsystem(gyro);
    drive.resetOdometry(new Pose2d(1.5, 2.75, Rotation2d.fromDegrees(30.0)));
    Alliance[] alliance = {Alliance.Blue};
    Command command =
        DriveCommands.resetHeadingToAllianceForwardCommand(drive, () -> Optional.of(alliance[0]));

    alliance[0] = Alliance.Red;
    command.initialize();

    assertTrue(command.getRequirements().contains(drive));
    assertTrue(command.runsWhenDisabled());
    assertEquals(1.5, drive.getPose().getX(), EPSILON);
    assertEquals(2.75, drive.getPose().getY(), EPSILON);
    assertEquals(180.0, drive.getPose().getRotation().getDegrees(), EPSILON);
    assertEquals(180.0, gyro.lastResetYawDegrees, EPSILON);
  }

  @Test
  void leavesHeadingUnchangedWhenAllianceIsUnavailable() {
    FakeGyro gyro = new FakeGyro();
    SwerveSubsystem drive = newSubsystem(gyro);
    Pose2d initialPose = new Pose2d(1.5, 2.75, Rotation2d.fromDegrees(30.0));
    drive.resetOdometry(initialPose);
    Command command = DriveCommands.resetHeadingToAllianceForwardCommand(drive, Optional::empty);

    command.initialize();

    assertEquals(initialPose, drive.getPose());
    assertEquals(0, gyro.resetCount);
  }

  private static SwerveSubsystem newSubsystem(FakeGyro gyro) {
    ModuleIO module = new ModuleIO() {};
    return new SwerveSubsystem(gyro, module, module, module, module);
  }

  private static class FakeGyro implements GyroIO {
    private int resetCount;
    private double lastResetYawDegrees;

    @Override
    public void resetYaw(double yawDegrees) {
      resetCount++;
      lastResetYawDegrees = yawDegrees;
    }
  }
}
