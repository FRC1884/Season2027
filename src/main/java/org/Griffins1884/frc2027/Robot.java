package org.Griffins1884.frc2027;

import static org.Griffins1884.frc2027.GlobalConstants.MODE;

import edu.wpi.first.wpilibj.DataLogManager;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Threads;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.Griffins1884.frc2027.util.LogRollover;
import org.Griffins1884.frc2027.util.RobotLogging;
import org.ironmaple.simulation.SimulatedArena;
import org.littletonrobotics.junction.LogFileUtil;
import org.littletonrobotics.junction.LoggedRobot;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.NT4Publisher;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.littletonrobotics.junction.wpilog.WPILOGWriter;
import org.littletonrobotics.urcl.URCL;

/** AdvantageKit-backed robot lifecycle for the reusable Season2027 base. */
public class Robot extends LoggedRobot {
  private long lastSchedulerNanos;
  private long lastContainerNanos;
  private Command autonomousCommand;
  private Command characterizationCommand;
  private final RobotContainer robotContainer;

  public Robot() {
    this(RobotContainer::new);
  }

  /**
   * Composition seam; desktop fixtures are supplied only by the separate performance source set.
   */
  protected Robot(java.util.function.Supplier<RobotContainer> containerFactory) {
    Logger.recordMetadata("ProjectName", "Season2027");
    Logger.recordMetadata("RuntimeMode", MODE.name());
    Logger.recordMetadata("LoggingMode", GlobalConstants.LOGGING_MODE.name());

    switch (MODE) {
      case REAL, SIM -> {
        String desktopLogDirectory =
            MODE == GlobalConstants.RobotMode.SIM
                ? System.getProperty("frc.performance.logDirectory", "")
                : "";
        Logger.addDataReceiver(
            desktopLogDirectory.isBlank()
                ? new WPILOGWriter()
                : new WPILOGWriter(desktopLogDirectory));
        Logger.addDataReceiver(new NT4Publisher());
      }
      case REPLAY -> {
        setUseTiming(false);
        String logPath = LogFileUtil.findReplayLog();
        Logger.setReplaySource(new WPILOGReader(logPath));
        Logger.addDataReceiver(new WPILOGWriter(LogFileUtil.addPathSuffix(logPath, "_sim")));
      }
    }
    LogRollover.init(null);
    Logger.registerURCL(URCL.startExternal());
    Logger.start();

    String desktopDataLogDirectory =
        MODE == GlobalConstants.RobotMode.SIM
            ? System.getProperty("frc.performance.logDirectory", "")
            : "";
    if (desktopDataLogDirectory.isBlank()) {
      DataLogManager.start();
    } else {
      DataLogManager.start(desktopDataLogDirectory);
    }
    DataLogManager.logNetworkTables(true);
    DriverStation.startDataLog(DataLogManager.getLog(), true);
    DriverStation.silenceJoystickConnectionWarning(MODE == GlobalConstants.RobotMode.SIM);

    robotContainer = containerFactory.get();
  }

  @Override
  public void robotPeriodic() {
    Threads.setCurrentThreadPriority(true, 99);
    try {
      long schedulerStart = System.nanoTime();
      CommandScheduler.getInstance().run();
      long containerStart = System.nanoTime();
      robotContainer.periodic();
      long end = System.nanoTime();
      lastSchedulerNanos = containerStart - schedulerStart;
      lastContainerNanos = end - containerStart;
      Logger.recordOutput("Robot/Performance/SchedulerMS", lastSchedulerNanos / 1e6);
      Logger.recordOutput("Robot/Performance/ContainerMS", lastContainerNanos / 1e6);
    } finally {
      Threads.setCurrentThreadPriority(false, 10);
    }
  }

  @Override
  public void disabledInit() {
    robotContainer.resetSimulationField();
  }

  @Override
  public void disabledPeriodic() {
    robotContainer.tryAutoZeroOdometryToAllianceWall();
  }

  @Override
  public void autonomousInit() {
    cancelCharacterization();
    autonomousCommand = createAutonomousCommand();
    if (autonomousCommand != null) {
      CommandScheduler.getInstance().schedule(autonomousCommand);
    }
  }

  @Override
  public void teleopInit() {
    if (autonomousCommand != null) {
      autonomousCommand.cancel();
      autonomousCommand = null;
    }
    cancelCharacterization();
  }

  @Override
  public void testInit() {
    CommandScheduler.getInstance().cancelAll();
    characterizationCommand = robotContainer.getCharacterizationCommand();
    if (characterizationCommand != null) {
      CommandScheduler.getInstance().schedule(characterizationCommand);
      RobotLogging.info("Characterization command scheduled: " + characterizationCommand.getName());
    }
  }

  @Override
  public void simulationPeriodic() {
    if (MODE == GlobalConstants.RobotMode.SIM) {
      SimulatedArena.getInstance().simulationPeriodic();
    }
  }

  /** Existing inclusive scheduler timing; read on the robot thread after its cycle. */
  public final long getLastSchedulerNanos() {
    return lastSchedulerNanos;
  }

  /** Existing container timing, excluding the scheduler; read on the robot thread. */
  public final long getLastContainerNanos() {
    return lastContainerNanos;
  }

  /** Lifecycle seam for desktop-only autonomous fixtures; normal selection is unchanged. */
  protected Command createAutonomousCommand() {
    return robotContainer.getAutonomousCommand();
  }

  /** Read-only access for the opt-in observer and desktop composition. */
  protected final RobotContainer getRobotContainer() {
    return robotContainer;
  }

  private void cancelCharacterization() {
    if (characterizationCommand != null) {
      characterizationCommand.cancel();
      characterizationCommand = null;
    }
  }

  @Override
  public void close() {
    try {
      robotContainer.close();
    } finally {
      super.close();
    }
  }
}
