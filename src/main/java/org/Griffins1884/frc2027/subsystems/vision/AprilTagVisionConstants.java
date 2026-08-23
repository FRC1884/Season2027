package org.Griffins1884.frc2027.subsystems.vision;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import org.Griffins1884.frc2027.util.LoggedTunableNumber;

/** Field-independent tuning values for AprilTag pose estimation. */
public final class AprilTagVisionConstants {
  public static final int LIMELIGHT_MEGATAG2_X_STDDEV_INDEX = 3;
  public static final int LIMELIGHT_MEGATAG2_Y_STDDEV_INDEX = 4;
  public static final int LIMELIGHT_MEGATAG2_YAW_STDDEV_INDEX = 5;
  public static final double TAG_PRESENCE_WEIGHT = 10.0;
  public static final double DISTANCE_WEIGHT = 7.0;
  public static final double POSE_AMBIGUITY_SHIFTER = 0.2;
  public static final double POSE_AMBIGUITY_MULTIPLIER = 4.0;

  private static final LoggedTunableNumber VISION_STDDEV_X =
      new LoggedTunableNumber("AprilTagVision/StdDev/X", 2.0);
  private static final LoggedTunableNumber VISION_STDDEV_Y =
      new LoggedTunableNumber("AprilTagVision/StdDev/Y", 2.0);
  private static final LoggedTunableNumber VISION_STDDEV_THETA =
      new LoggedTunableNumber("AprilTagVision/StdDev/Theta", 1.0);
  private static final LoggedTunableNumber LIMELIGHT_MT1_STDDEV_X =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT1/X", 1.2);
  private static final LoggedTunableNumber LIMELIGHT_MT1_STDDEV_Y =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT1/Y", 1.2);
  private static final LoggedTunableNumber LIMELIGHT_MT1_STDDEV_YAW =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT1/YawDeg", 6.0);
  private static final LoggedTunableNumber LIMELIGHT_MT2_STDDEV_X =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT2/X", 0.7);
  private static final LoggedTunableNumber LIMELIGHT_MT2_STDDEV_Y =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT2/Y", 0.7);
  private static final LoggedTunableNumber LIMELIGHT_MT2_STDDEV_YAW =
      new LoggedTunableNumber("AprilTagVision/Limelight/StdDev/MT2/YawDeg", 4.0);
  private static final LoggedTunableNumber MAX_YAW_RATE_DEG_PER_SEC =
      new LoggedTunableNumber("AprilTagVision/Limelight/MaxYawRateDegPerSec", 180.0);
  private static final LoggedTunableNumber SINGLE_TAG_QUALITY_CUTOFF =
      new LoggedTunableNumber("AprilTagVision/Limelight/SingleTagQualityCutoff", 0.3);
  public static final LoggedTunableNumber LIMELIGHT_MAX_TRANSLATION_RESIDUAL_METERS =
      new LoggedTunableNumber("AprilTagVision/Limelight/MaxTranslationResidualMeters", 2.5);
  public static final LoggedTunableNumber LIMELIGHT_REJECT_OUTLIERS =
      new LoggedTunableNumber("AprilTagVision/Limelight/RejectOutliers", 1.0);

  private AprilTagVisionConstants() {}

  public static Matrix<N3, N1> getVisionMeasurementStdDevs() {
    return VecBuilder.fill(VISION_STDDEV_X.get(), VISION_STDDEV_Y.get(), VISION_STDDEV_THETA.get());
  }

  public static double[] getLimelightStandardDeviations() {
    return new double[] {
      LIMELIGHT_MT1_STDDEV_X.get(),
      LIMELIGHT_MT1_STDDEV_Y.get(),
      Math.toRadians(LIMELIGHT_MT1_STDDEV_YAW.get()),
      LIMELIGHT_MT2_STDDEV_X.get(),
      LIMELIGHT_MT2_STDDEV_Y.get(),
      Math.toRadians(LIMELIGHT_MT2_STDDEV_YAW.get())
    };
  }

  public static double getLimelightMaxYawRateDegPerSec() {
    return MAX_YAW_RATE_DEG_PER_SEC.get();
  }

  public static double getMegatag2SingleTagQualityCutoff() {
    return SINGLE_TAG_QUALITY_CUTOFF.get();
  }
}
