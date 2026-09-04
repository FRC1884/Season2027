package org.Griffins1884.frc2027;

import edu.wpi.first.wpilibj.RobotBase;
import java.util.Locale;
import org.Griffins1884.frc2027.util.LoggedTunableNumber;

/** Robot-wide constants that are independent of any season field or game piece. */
public final class GlobalConstants {
  public static final RobotMode MODE = determineRobotMode();
  public static final RobotType ROBOT = RobotType.SIMBOT;
  public static final LoggingMode LOGGING_MODE =
      MODE == RobotMode.REAL ? LoggingMode.COMP : LoggingMode.DEBUG;
  public static final double ODOMETRY_FREQUENCY = 250.0;
  public static boolean TUNING_MODE = false;

  private GlobalConstants() {}

  public enum RobotMode {
    REAL,
    SIM,
    REPLAY
  }

  /** Existing chassis profiles retained without changing their inherited hardware values. */
  public enum RobotType {
    COMPBOT,
    DBOT,
    SIMBOT
  }

  public enum LoggingMode {
    DEBUG,
    COMP
  }

  public static boolean isDebugMode() {
    return LOGGING_MODE == LoggingMode.DEBUG;
  }

  public static boolean isCompMode() {
    return LOGGING_MODE == LoggingMode.COMP;
  }

  private static RobotMode determineRobotMode() {
    String override = System.getProperty("frc.mode", "").trim().toLowerCase(Locale.ROOT);
    return switch (override) {
      case "real" -> RobotMode.REAL;
      case "replay" -> RobotMode.REPLAY;
      case "sim" -> RobotMode.SIM;
      default -> RobotBase.isReal() ? RobotMode.REAL : RobotMode.SIM;
    };
  }

  /** PID and feedforward gains with runtime tuning support. */
  public record Gains(
      LoggedTunableNumber kP,
      LoggedTunableNumber kI,
      LoggedTunableNumber kD,
      LoggedTunableNumber kS,
      LoggedTunableNumber kV,
      LoggedTunableNumber kA,
      LoggedTunableNumber kG) {
    public Gains(String prefix, double kP, double kI, double kD) {
      this(prefix, kP, kI, kD, 0.0, 0.0, 0.0, 0.0);
    }

    public Gains(String prefix, double kP, double kI, double kD, double kS, double kV, double kA) {
      this(prefix, kP, kI, kD, kS, kV, kA, 0.0);
    }

    public Gains(
        String prefix,
        double kP,
        double kI,
        double kD,
        double kS,
        double kV,
        double kA,
        double kG) {
      this(
          new LoggedTunableNumber(prefix + "/kP", kP),
          new LoggedTunableNumber(prefix + "/kI", kI),
          new LoggedTunableNumber(prefix + "/kD", kD),
          new LoggedTunableNumber(prefix + "/kS", kS),
          new LoggedTunableNumber(prefix + "/kV", kV),
          new LoggedTunableNumber(prefix + "/kA", kA),
          new LoggedTunableNumber(prefix + "/kG", kG));
    }
  }
}
