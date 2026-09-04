package org.Griffins1884.frc2027;

import edu.wpi.first.wpilibj.TimedRobot;

/**
 * Season 2027 robot shell.
 *
 * <p>No mechanisms are intentionally implemented yet. Student feature work should begin only after
 * the repository governance and Harness controls are active.
 */
public class Robot extends TimedRobot {
  private final RobotContainer robotContainer = new RobotContainer();

  @Override
  public void robotInit() {
    robotContainer.initialize();
  }
}
