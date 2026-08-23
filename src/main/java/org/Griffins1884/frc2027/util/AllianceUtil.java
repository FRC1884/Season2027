package org.Griffins1884.frc2027.util;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import java.util.Optional;

/** Alliance state helper that does not encode a season field's dimensions or coordinates. */
public final class AllianceUtil {
  private static Optional<Alliance> cachedAlliance = Optional.empty();

  private AllianceUtil() {}

  public static Optional<Alliance> getAlliance() {
    Optional<Alliance> liveAlliance = DriverStation.getAlliance();
    liveAlliance.ifPresent(alliance -> cachedAlliance = Optional.of(alliance));
    return liveAlliance.isPresent() ? liveAlliance : cachedAlliance;
  }

  public static boolean shouldFlip() {
    return getAlliance().orElse(Alliance.Blue) == Alliance.Red;
  }

  static void clearCachedAllianceForTesting() {
    cachedAlliance = Optional.empty();
  }
}
