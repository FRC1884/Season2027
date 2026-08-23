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
  private Command autonomousCommand;
  private Command characterizationCommand;
  private final RobotContainer robotContainer;

  public Robot() {
    Logger.recordMetadata("ProjectName", "Season2027");
    Logger.recordMetadata("RuntimeMode", MODE.name());
    Logger.recordMetadata("LoggingMode", GlobalConstants.LOGGING_MODE.name());

    switch (MODE) {
      case REAL, SIM -> {
        Logger.addDataReceiver(new WPILOGWriter());
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

    DataLogManager.start();
    DataLogManager.logNetworkTables(true);
    DriverStation.startDataLog(DataLogManager.getLog(), true);
    DriverStation.silenceJoystickConnectionWarning(MODE == GlobalConstants.RobotMode.SIM);

    robotContainer = new RobotContainer();
  }

  @Override
  public void robotPeriodic() {
    Threads.setCurrentThreadPriority(true, 99);
    try {
      CommandScheduler.getInstance().run();
      robotContainer.periodic();
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
    autonomousCommand = robotContainer.getAutonomousCommand();
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

  private void cancelCharacterization() {
    if (characterizationCommand != null) {
      characterizationCommand.cancel();
      characterizationCommand = null;
    }
  }
}
