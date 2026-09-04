package org.Griffins1884.frc2027;

import static org.Griffins1884.frc2027.Config.Controllers.getDriverController;
import static org.Griffins1884.frc2027.Config.Subsystems.AUTONOMOUS_ENABLED;
import static org.Griffins1884.frc2027.Config.Subsystems.DRIVETRAIN_ENABLED;
import static org.Griffins1884.frc2027.GlobalConstants.MODE;
import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.BACK_LEFT;
import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.BACK_RIGHT;
import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.FRONT_LEFT;
import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.FRONT_RIGHT;
import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.GYRO_TYPE;

import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.config.ModuleConfig;
import com.pathplanner.lib.config.PIDConstants;
import com.pathplanner.lib.config.RobotConfig;
import com.pathplanner.lib.controllers.PPHolonomicDriveController;
import com.pathplanner.lib.util.PPLibTelemetry;
import com.pathplanner.lib.util.PathPlannerLogging;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import java.util.Optional;
import org.Griffins1884.frc2027.GlobalConstants.RobotMode;
import org.Griffins1884.frc2027.OI.DriverMap;
import org.Griffins1884.frc2027.commands.DriveCommands;
import org.Griffins1884.frc2027.simulation.GenericSimArena;
import org.Griffins1884.frc2027.simulation.visualization.RobotStateVisualizer;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIO;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIONavX;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIOPigeon2;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIOSim;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIO;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIOFullKraken;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIOSim;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;
import org.Griffins1884.frc2027.subsystems.vision.Vision;
import org.Griffins1884.frc2027.web.OperatorBoardServer;
import org.ironmaple.simulation.SimulatedArena;
import org.ironmaple.simulation.drivesims.SwerveDriveSimulation;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.LoggedDashboardChooser;

/** Composition root for the Season2027 drivetrain and reusable infrastructure base. */
public final class RobotContainer implements AutoCloseable {
  private final SwerveSubsystem drive;
  private final SwerveDriveSimulation driveSimulation;
  private final DriverMap driver = getDriverController();
  private final LoggedDashboardChooser<Command> characterizationChooser =
      new LoggedDashboardChooser<>("Characterization/Diagnostics");
  private final Command characterizationIdleCommand = Commands.none();
  private final SendableChooser<Command> autonomousChooser;
  private final RobotStateVisualizer robotStateVisualizer;
  private final Vision vision;
  private final OperatorBoardServer operatorBoardServer;
  private boolean autoAllianceZeroed;

  public RobotContainer() {
    characterizationChooser.addDefaultOption("None", characterizationIdleCommand);

    DriveBuildResult driveBuild =
        DRIVETRAIN_ENABLED ? buildDrive() : new DriveBuildResult(null, null);
    drive = driveBuild.subsystem();
    driveSimulation = driveBuild.simulation();
    vision =
        drive != null
            ? new Vision(drive::accept)
            : new Vision((pose, timestamp, standardDeviations) -> {});
    if (drive != null) {
      drive.setOdometryResetListener(
          () -> {
            vision.resetPoseHistory();
            vision.suppressVisionForSeconds(0.35);
          });
    }

    robotStateVisualizer =
        new RobotStateVisualizer(
            drive,
            driveSimulation != null
                ? () -> new Pose3d(driveSimulation.getSimulatedDriveTrainPose())
                : null);
    operatorBoardServer = OperatorBoardServer.startDefault();

    registerCharacterizationOptions();
    configureDriverButtonBindings();
    configurePathPlanner();
    autonomousChooser =
        AutoBuilder.isConfigured() ? AutoBuilder.buildAutoChooser() : emptyAutoChooser();
    SmartDashboard.putData("Autonomous", autonomousChooser);
  }

  private DriveBuildResult buildDrive() {
    return switch (MODE) {
      case REAL ->
          new DriveBuildResult(
              new SwerveSubsystem(
                  switch (GYRO_TYPE) {
                    case PIGEON -> new GyroIOPigeon2();
                    case NAVX -> new GyroIONavX();
                    case ADIS -> new GyroIO() {};
                  },
                  new ModuleIOFullKraken(FRONT_LEFT),
                  new ModuleIOFullKraken(FRONT_RIGHT),
                  new ModuleIOFullKraken(BACK_LEFT),
                  new ModuleIOFullKraken(BACK_RIGHT)),
              null);
      case SIM -> buildSimDrive();
      case REPLAY ->
          new DriveBuildResult(
              new SwerveSubsystem(
                  new GyroIO() {},
                  new ModuleIO() {},
                  new ModuleIO() {},
                  new ModuleIO() {},
                  new ModuleIO() {}),
              null);
    };
  }

  private DriveBuildResult buildSimDrive() {
    GenericSimArena.install();
    SwerveDriveSimulation simulation =
        new SwerveDriveSimulation(SwerveConstants.MAPLE_SIM_CONFIG, Pose2d.kZero);
    SimulatedArena.getInstance().addDriveTrainSimulation(simulation);

    SwerveSubsystem subsystem =
        new SwerveSubsystem(
            new GyroIOSim(simulation.getGyroSimulation()),
            new ModuleIOSim(simulation.getModules()[0]),
            new ModuleIOSim(simulation.getModules()[1]),
            new ModuleIOSim(simulation.getModules()[2]),
            new ModuleIOSim(simulation.getModules()[3]));
    return new DriveBuildResult(subsystem, simulation);
  }

  private void registerCharacterizationOptions() {
    if (drive == null) {
      return;
    }
    characterizationChooser.addOption(
        "Drive | SysId (Full Routine)", drive.sysIdRoutine().ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | Wheel Radius Characterization",
        DriveCommands.wheelRadiusCharacterization(drive).ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | Feedforward Characterization",
        DriveCommands.feedforwardCharacterization(drive).ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | SysId (Quasistatic Forward)",
        drive.sysIdQuasistatic(SysIdRoutine.Direction.kForward).ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | SysId (Quasistatic Reverse)",
        drive.sysIdQuasistatic(SysIdRoutine.Direction.kReverse).ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | SysId (Dynamic Forward)",
        drive.sysIdDynamic(SysIdRoutine.Direction.kForward).ignoringDisable(true));
    characterizationChooser.addOption(
        "Drive | SysId (Dynamic Reverse)",
        drive.sysIdDynamic(SysIdRoutine.Direction.kReverse).ignoringDisable(true));
    characterizationChooser.addOption(
        "Turn | SysId (Full Routine)", drive.sysIdTurnRoutine().ignoringDisable(true));
  }

  private void configureDriverButtonBindings() {
    if (drive == null) {
      return;
    }
    drive.setDefaultCommand(
        DriveCommands.joystickDriveCommand(
            drive, driver.getYAxis(), driver.getXAxis(), driver.getRotAxis()));
    driver
        .robotRelativeOverride()
        .whileTrue(
            DriveCommands.joystickDriveRobotRelativeFlippedCommand(
                drive, driver.getYAxis(), driver.getXAxis(), driver.getRotAxis()));
    driver.resetHeading().onTrue(DriveCommands.resetHeadingToAllianceForwardCommand(drive));
    driver
        .resetOdometry()
        .onTrue(
            Commands.runOnce(
                    () -> {
                      Optional<Alliance> alliance = DriverStation.getAlliance();
                      if (alliance.isEmpty()) {
                        Logger.recordOutput("Odometry/AllianceZero/Failed", true);
                        return;
                      }
                      drive.zeroGyroAndOdometryToAllianceWall(alliance.get());
                    },
                    drive)
                .ignoringDisable(true));
  }

  private void configurePathPlanner() {
    if (drive == null) {
      return;
    }
    RobotConfig robotConfig = createPathPlannerRobotConfig();

    AutoBuilder.configure(
        drive::getPose,
        drive::resetOdometry,
        drive::getRobotRelativeSpeeds,
        drive::runVelocity,
        new PPHolonomicDriveController(new PIDConstants(5.0), new PIDConstants(5.0)),
        robotConfig,
        () -> DriverStation.getAlliance().orElse(Alliance.Blue) == Alliance.Red,
        drive);
    PathPlannerLogging.setLogCurrentPoseCallback(
        pose -> Logger.recordOutput("PathPlanner/CurrentPose", pose));
    PathPlannerLogging.setLogTargetPoseCallback(
        pose -> Logger.recordOutput("PathPlanner/TargetPose", pose));
    PathPlannerLogging.setLogActivePathCallback(
        poses -> Logger.recordOutput("PathPlanner/ActivePath", poses.toArray(Pose2d[]::new)));
  }

  static RobotConfig createPathPlannerRobotConfig() {
    ModuleConfig moduleConfig =
        new ModuleConfig(
            SwerveConstants.getWheelRadiusMeters(),
            SwerveConstants.MAX_LINEAR_SPEED,
            SwerveConstants.WHEEL_FRICTION_COEFF,
            SwerveConstants.DRIVE_GEARBOX.withReduction(SwerveConstants.KRAKEN_DRIVE_GEAR_RATIO),
            SwerveConstants.KRAKEN_DRIVE_CURRENT_LIMIT,
            1);
    return new RobotConfig(
        SwerveConstants.ROBOT_MASS,
        SwerveConstants.ROBOT_INERTIA,
        moduleConfig,
        SwerveConstants.MODULE_TRANSLATIONS);
  }

  private static SendableChooser<Command> emptyAutoChooser() {
    SendableChooser<Command> chooser = new SendableChooser<>();
    chooser.setDefaultOption("None", Commands.none());
    return chooser;
  }

  public Command getAutonomousCommand() {
    return AUTONOMOUS_ENABLED ? autonomousChooser.getSelected() : null;
  }

  public Command getCharacterizationCommand() {
    Command selected = characterizationChooser.get();
    return selected == characterizationIdleCommand ? null : selected;
  }

  public void tryAutoZeroOdometryToAllianceWall() {
    if (autoAllianceZeroed || drive == null || !DriverStation.isDisabled()) {
      return;
    }
    DriverStation.getAlliance()
        .ifPresent(
            alliance -> {
              drive.zeroGyroAndOdometryToAllianceWall(alliance);
              autoAllianceZeroed = true;
            });
  }

  public void resetSimulationField() {
    if (MODE != RobotMode.SIM || driveSimulation == null || drive == null) {
      return;
    }
    driveSimulation.setSimulationWorldPose(Pose2d.kZero);
    drive.resetOdometry(Pose2d.kZero, true);
    SimulatedArena.getInstance().resetFieldForAuto();
  }

  public void periodic() {
    if (drive != null) {
      PPLibTelemetry.setCurrentPose(drive.getPose());
    }
    robotStateVisualizer.periodic();
  }

  @Override
  public void close() {
    if (operatorBoardServer != null) {
      operatorBoardServer.close();
    }
  }

  private record DriveBuildResult(SwerveSubsystem subsystem, SwerveDriveSimulation simulation) {}
}
