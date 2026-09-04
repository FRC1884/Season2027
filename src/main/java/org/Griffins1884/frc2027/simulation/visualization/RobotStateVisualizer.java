package org.Griffins1884.frc2027.simulation.visualization;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import java.util.function.Supplier;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;
import org.littletonrobotics.junction.Logger;

/** Publishes the reusable drivetrain pose for AdvantageScope. */
public final class RobotStateVisualizer {
  private final SwerveSubsystem drive;
  private final Supplier<Pose3d> simulationPoseSupplier;

  public RobotStateVisualizer(SwerveSubsystem drive, Supplier<Pose3d> simulationPoseSupplier) {
    this.drive = drive;
    this.simulationPoseSupplier = simulationPoseSupplier;
  }

  public void periodic() {
    Pose2d odometryPose = drive != null ? drive.getPose() : null;
    if (!isValidPose(odometryPose)) {
      Logger.recordOutput("FieldSimulation/RobotPosition", new Pose3d());
      Logger.recordOutput("FieldSimulation/RobotPose3d", new Pose3d());
      return;
    }

    Pose3d simulationPose = simulationPoseSupplier != null ? simulationPoseSupplier.get() : null;
    Pose3d publishedPose =
        isValidPose3d(simulationPose) ? simulationPose : new Pose3d(odometryPose);
    Logger.recordOutput("FieldSimulation/RobotPosition", publishedPose);
    Logger.recordOutput("FieldSimulation/RobotPose3d", publishedPose);
  }

  private static boolean isValidPose(Pose2d pose) {
    return pose != null
        && Double.isFinite(pose.getX())
        && Double.isFinite(pose.getY())
        && Double.isFinite(pose.getRotation().getRadians());
  }

  private static boolean isValidPose3d(Pose3d pose) {
    return pose != null
        && Double.isFinite(pose.getX())
        && Double.isFinite(pose.getY())
        && Double.isFinite(pose.getZ());
  }
}
