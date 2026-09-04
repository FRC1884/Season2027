package org.Griffins1884.frc2027;

import static org.Griffins1884.frc2027.GlobalConstants.ROBOT;

import org.Griffins1884.frc2027.OI.DriverMap;
import org.Griffins1884.frc2027.OI.PS5DriverMap;
import org.Griffins1884.frc2027.OI.PS5ProDriverMap;
import org.Griffins1884.frc2027.OI.SimXboxUniversalMap;
import org.Griffins1884.frc2027.OI.XboxDriverMap;

/** Runtime feature selection for the clean Season2027 base. */
public final class Config {
  private Config() {}

  public static final class Subsystems {
    public static final boolean DRIVETRAIN_ENABLED = true;
    public static final boolean AUTONOMOUS_ENABLED = true;

    /** Cameras remain disabled until approved 2027 transforms and field data are available. */
    public static final boolean VISION_ENABLED = false;

    public static final boolean WEBUI_ENABLED = true;

    private Subsystems() {}
  }

  public static final class WebUIConfig {
    public static final boolean ENABLED = Subsystems.WEBUI_ENABLED;
    public static final String BIND_ADDRESS = System.getProperty("frc.web.bind", "0.0.0.0");
    public static final int PORT = Integer.getInteger("frc.web.port", 5805);

    private WebUIConfig() {}
  }

  public static final class Controllers {
    public enum DriverControllerType {
      XBOX,
      PS5,
      PS5_PRO,
      SIM_XBOX_UNIVERSAL
    }

    public static final int DRIVER_PORT = 0;
    public static final DriverControllerType COMPBOT_DRIVER = DriverControllerType.PS5_PRO;
    public static final DriverControllerType DBOT_DRIVER = DriverControllerType.PS5_PRO;
    public static final DriverControllerType SIMBOT_DRIVER =
        DriverControllerType.SIM_XBOX_UNIVERSAL;

    private Controllers() {}

    public static DriverMap getDriverController() {
      return switch (ROBOT) {
        case COMPBOT -> createDriverController(COMPBOT_DRIVER);
        case DBOT -> createDriverController(DBOT_DRIVER);
        case SIMBOT -> createDriverController(SIMBOT_DRIVER);
      };
    }

    private static DriverMap createDriverController(DriverControllerType type) {
      return switch (type) {
        case XBOX -> new XboxDriverMap(DRIVER_PORT);
        case PS5 -> new PS5DriverMap(DRIVER_PORT);
        case PS5_PRO -> new PS5ProDriverMap(DRIVER_PORT);
        case SIM_XBOX_UNIVERSAL -> new SimXboxUniversalMap(DRIVER_PORT);
      };
    }
  }
}
