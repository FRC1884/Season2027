package org.Griffins1884.frc2027.subsystems.swerve;

import edu.wpi.first.wpilibj.Preferences;

public final class SwerveCalibration {
  private static final String WHEEL_RADIUS_KEY = "Swerve/Calibration/WheelRadiusMeters";
  private static final String MODULE_ZERO_TRIM_PREFIX =
      "Swerve/Calibration/ModuleZeroTrimRotations/";

  private static final java.util.concurrent.atomic.AtomicLong revision =
      new java.util.concurrent.atomic.AtomicLong();

  private SwerveCalibration() {}

  static long revision() {
    return revision.get();
  }

  public static double getWheelRadiusMeters(double fallbackMeters) {
    return Preferences.getDouble(WHEEL_RADIUS_KEY, fallbackMeters);
  }

  public static void setWheelRadiusMeters(double wheelRadiusMeters) {
    Preferences.setDouble(WHEEL_RADIUS_KEY, wheelRadiusMeters);
    revision.incrementAndGet();
  }

  public static void clearWheelRadiusMeters() {
    Preferences.remove(WHEEL_RADIUS_KEY);
    revision.incrementAndGet();
  }

  public static double getModuleZeroTrimRotations(String moduleKey) {
    return Preferences.getDouble(moduleZeroTrimKey(moduleKey), 0.0);
  }

  public static void setModuleZeroTrimRotations(String moduleKey, double rotations) {
    Preferences.setDouble(moduleZeroTrimKey(moduleKey), rotations);
    revision.incrementAndGet();
  }

  public static void clearModuleZeroTrimRotations(String moduleKey) {
    Preferences.remove(moduleZeroTrimKey(moduleKey));
    revision.incrementAndGet();
  }

  public static String moduleKey(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]+", "");
  }

  private static String moduleZeroTrimKey(String moduleKey) {
    return MODULE_ZERO_TRIM_PREFIX + moduleKey;
  }
}
