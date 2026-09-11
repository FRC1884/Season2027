package org.Griffins1884.frc2027;

import static org.Griffins1884.frc2027.GlobalConstants.ROBOT;

import org.Griffins1884.frc2027.OI.DriverMap;
import org.Griffins1884.frc2027.OI.PS5DriverMap;
import org.Griffins1884.frc2027.OI.PS5ProDriverMap;
import org.Griffins1884.frc2027.OI.SimXboxUniversalMap;
import org.Griffins1884.frc2027.OI.XboxDriverMap;

public final class Config {

  public static final class Subsystems {
    public static final boolean DRIVETRAIN_ENABLED = true;
    public static final boolean LEDS_ENABLED = false;
    public static final boolean AUTONOMOUS_ENABLED = true;
    public static final boolean VISION_ENABLED = true;
    public static final boolean WEBUI_ENABLED = true;
    public static final boolean TURRET_ENABLED = true;
    public static final boolean SHOOTER_ENABLED = true;
    public static final boolean SHOOTER_PIVOT_ENABLED = true;
    public static final boolean INTAKE_PIVOT_ENABLED = true;
    public static final boolean INTAKE_ENABLED = true;
    public static final boolean INDEXER_ENABLED = true;
    public static final boolean TOOTH_ROLLOUT_ENABLED = false;
    public static final boolean SPINDEXER_ENABLED = true;
  }

  public static final class WebUIConfig {
    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    private static final int DEFAULT_PORT = 5805;

    public static final boolean ENABLED = Subsystems.WEBUI_ENABLED;
    public static final String BIND_ADDRESS = DEFAULT_BIND_ADDRESS;
    public static final int PORT = DEFAULT_PORT;
  }

  public static final class Controllers {
    public enum DriverControllerType {
      XBOX,
      PS4,
      PS5,
      PS5_PRO,
      SIM_XBOX_UNIVERSAL
    }

    public static final int DRIVER_PORT = 0;
    public static final DriverControllerType COMPBOT_DRIVER = DriverControllerType.PS5_PRO;
    public static final DriverControllerType SIMBOT_DRIVER = DriverControllerType.SIM_XBOX_UNIVERSAL;

    public static DriverMap getDriverController() {
      return createDriverController(resolveFallbackControllerType());
    }

    private static DriverControllerType resolveFallbackControllerType() {
      return switch (ROBOT) {
        case COMPBOT -> COMPBOT_DRIVER;
        case SIMBOT -> SIMBOT_DRIVER;
      };
    }

    private static DriverMap createDriverController(DriverControllerType type) {
      return switch (type) {
        case XBOX -> new XboxDriverMap(DRIVER_PORT);
        case PS4, PS5 -> new PS5DriverMap(DRIVER_PORT);
        case PS5_PRO -> new PS5ProDriverMap(DRIVER_PORT);
        case SIM_XBOX_UNIVERSAL -> new SimXboxUniversalMap(DRIVER_PORT);
      };
    }
  }
}
