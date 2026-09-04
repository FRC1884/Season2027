package org.Griffins1884.frc2027;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pathplanner.lib.config.RobotConfig;
import org.junit.jupiter.api.Test;

class RobotContainerPathPlannerTest {
  @Test
  void retainedChassisProducesValidPathPlannerConfiguration() throws Exception {
    RobotConfig config = RobotContainer.createPathPlannerRobotConfig();
    RobotConfig deployConfig = RobotConfig.fromGUISettings();

    assertTrue(config.isHolonomic);
    assertEquals(4, config.numModules);
    assertEquals(config.massKG, deployConfig.massKG, 1.0e-9);
    assertEquals(config.MOI, deployConfig.MOI, 1.0e-9);
    assertEquals(
        config.moduleConfig.wheelRadiusMeters, deployConfig.moduleConfig.wheelRadiusMeters, 1.0e-9);
    assertEquals(
        config.moduleConfig.maxDriveVelocityMPS,
        deployConfig.moduleConfig.maxDriveVelocityMPS,
        1.0e-9);
    assertEquals(
        config.moduleConfig.driveCurrentLimit, deployConfig.moduleConfig.driveCurrentLimit, 1.0e-9);
    for (int index = 0; index < config.moduleLocations.length; index++) {
      assertEquals(
          config.moduleLocations[index].getX(), deployConfig.moduleLocations[index].getX(), 1.0e-9);
      assertEquals(
          config.moduleLocations[index].getY(), deployConfig.moduleLocations[index].getY(), 1.0e-9);
    }
  }
}
