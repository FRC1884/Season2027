package org.Griffins1884.frc2027.subsystems.vision;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.wpilibj.Alert;
import edu.wpi.first.wpilibj.Alert.AlertType;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.littletonrobotics.junction.Logger;

/**
 * Multi-camera AprilTag pose pipeline with no hard-coded field layout or target-tag assumptions.
 */
public final class Vision extends SubsystemBase {
  private final VisionConsumer consumer;
  private final VisionIO[] io;
  private final VisionIO.VisionIOInputs[] inputs;
  private final Alert[] disconnectedAlerts;
  private final double[] lastAcceptedTimestamps;
  private boolean useVision = true;
  private Integer exclusiveTagId;
  private double suppressUntilTimestamp;
  private boolean anyCameraHasAcceptedPose;

  public Vision(VisionConsumer consumer, VisionIO... io) {
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.io = io != null ? io.clone() : new VisionIO[0];
    this.inputs = new VisionIO.VisionIOInputs[this.io.length];
    this.disconnectedAlerts = new Alert[this.io.length];
    this.lastAcceptedTimestamps = new double[this.io.length];
    for (int index = 0; index < this.io.length; index++) {
      this.io[index] = Objects.requireNonNull(this.io[index], "io[" + index + "]");
      inputs[index] = new VisionIO.VisionIOInputs();
      String cameraName = this.io[index].getCameraConstants().cameraName();
      disconnectedAlerts[index] =
          new Alert("Vision camera disconnected: " + cameraName, AlertType.kWarning);
      lastAcceptedTimestamps[index] = Double.NEGATIVE_INFINITY;
    }
  }

  @Override
  public void periodic() {
    List<AcceptedMeasurement> acceptedMeasurements = new ArrayList<>();
    anyCameraHasAcceptedPose = false;

    for (int index = 0; index < io.length; index++) {
      VisionIO.VisionIOInputs cameraInputs = inputs[index];
      io[index].updateInputs(cameraInputs);
      String cameraName = io[index].getCameraConstants().cameraName();
      String key = "AprilTagVision/" + cameraName;

      disconnectedAlerts[index].set(!cameraInputs.connected);
      Logger.recordOutput(key + "/Connected", cameraInputs.connected);
      Logger.recordOutput(key + "/SeesTarget", cameraInputs.seesTarget);
      Logger.recordOutput(key + "/TagCount", cameraInputs.megatagCount);
      Logger.recordOutput(key + "/RejectReason", cameraInputs.rejectReason.name());

      AcceptedMeasurement measurement = validate(index, cameraInputs);
      if (measurement != null) {
        acceptedMeasurements.add(measurement);
        lastAcceptedTimestamps[index] = measurement.timestampSeconds();
        Logger.recordOutput(key + "/Accepted", true);
      } else {
        Logger.recordOutput(key + "/Accepted", false);
      }
    }

    acceptedMeasurements.sort(Comparator.comparingDouble(AcceptedMeasurement::timestampSeconds));
    for (AcceptedMeasurement measurement : acceptedMeasurements) {
      consumer.accept(measurement.pose(), measurement.timestampSeconds(), measurement.stdDevs());
      anyCameraHasAcceptedPose = true;
    }
    Logger.recordOutput("AprilTagVision/AnyCameraAcceptedPose", anyCameraHasAcceptedPose);
  }

  private AcceptedMeasurement validate(int cameraIndex, VisionIO.VisionIOInputs cameraInputs) {
    if (!useVision
        || Timer.getFPGATimestamp() < suppressUntilTimestamp
        || !cameraInputs.connected
        || cameraInputs.megatagPoseEstimate == null) {
      return null;
    }

    MegatagPoseEstimate estimate = cameraInputs.megatagPoseEstimate;
    Pose2d pose = estimate.fieldToRobot();
    if (!isFinitePose(pose)
        || !Double.isFinite(estimate.timestampSeconds())
        || estimate.timestampSeconds() <= lastAcceptedTimestamps[cameraIndex]) {
      return null;
    }
    int[] fiducialIds = estimate.fiducialIds();
    if (fiducialIds.length == 0 || !containsExclusiveTag(fiducialIds)) {
      return null;
    }
    if (fiducialIds.length == 1
        && estimate.quality() < AprilTagVisionConstants.getMegatag2SingleTagQualityCutoff()) {
      return null;
    }
    if (AprilTagVisionConstants.LIMELIGHT_REJECT_OUTLIERS.get() > 0.5
        && Double.isFinite(estimate.residualTranslation())
        && estimate.residualTranslation()
            > AprilTagVisionConstants.LIMELIGHT_MAX_TRANSLATION_RESIDUAL_METERS.get()
        && fiducialIds.length < 2) {
      return null;
    }

    double[] standardDeviations = cameraInputs.standardDeviations;
    if (standardDeviations == null
        || standardDeviations.length
            <= AprilTagVisionConstants.LIMELIGHT_MEGATAG2_YAW_STDDEV_INDEX) {
      standardDeviations = AprilTagVisionConstants.getLimelightStandardDeviations();
    }
    double x = standardDeviations[AprilTagVisionConstants.LIMELIGHT_MEGATAG2_X_STDDEV_INDEX];
    double y = standardDeviations[AprilTagVisionConstants.LIMELIGHT_MEGATAG2_Y_STDDEV_INDEX];
    double theta = standardDeviations[AprilTagVisionConstants.LIMELIGHT_MEGATAG2_YAW_STDDEV_INDEX];
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(theta)) {
      return null;
    }
    Matrix<N3, N1> stdDevs = VecBuilder.fill(x, y, theta);
    return new AcceptedMeasurement(pose, estimate.timestampSeconds(), stdDevs);
  }

  private boolean containsExclusiveTag(int[] ids) {
    if (exclusiveTagId == null) {
      return true;
    }
    for (int id : ids) {
      if (id == exclusiveTagId) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFinitePose(Pose2d pose) {
    return pose != null
        && Double.isFinite(pose.getX())
        && Double.isFinite(pose.getY())
        && Double.isFinite(pose.getRotation().getRadians());
  }

  public void setUseVision(boolean useVision) {
    this.useVision = useVision;
  }

  public void setExclusiveTagId(int id) {
    exclusiveTagId = id;
  }

  public void clearExclusiveTagId() {
    exclusiveTagId = null;
  }

  public void resetPoseHistory() {
    for (int index = 0; index < lastAcceptedTimestamps.length; index++) {
      lastAcceptedTimestamps[index] = Double.NEGATIVE_INFINITY;
    }
  }

  public void suppressVisionForSeconds(double seconds) {
    suppressUntilTimestamp = Timer.getFPGATimestamp() + Math.max(0.0, seconds);
  }

  public boolean anyCameraHasAcceptedPose() {
    return anyCameraHasAcceptedPose;
  }

  @FunctionalInterface
  public interface VisionConsumer {
    void accept(
        Pose2d visionRobotPoseMeters,
        double timestampSeconds,
        Matrix<N3, N1> visionMeasurementStdDevs);
  }

  private record AcceptedMeasurement(
      Pose2d pose, double timestampSeconds, Matrix<N3, N1> stdDevs) {}
}
