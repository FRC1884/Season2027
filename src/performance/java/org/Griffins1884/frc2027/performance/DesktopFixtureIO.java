package org.Griffins1884.frc2027.performance;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.DriverStation;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.RobotContainer;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIO;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIO;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants;

/**
 * Deterministic sensor fixture, not a physics or hardware performance model. The production
 * command, setpoint, module, and estimator code runs unchanged. Requested module velocities are
 * followed ideally, and production WPILib kinematics determines gyro rotation. No motor controller
 * is opened.
 */
final class DesktopFixtureIO {
  private final FixtureModule[] modules = new FixtureModule[4];
  private final SwerveDriveKinematics kinematics =
      new SwerveDriveKinematics(SwerveConstants.MODULE_TRANSLATIONS);
  private final SwerveModuleState[] states = new SwerveModuleState[4];
  private Rotation2d yaw = Rotation2d.kZero;
  private final GyroIO gyro =
      new GyroIO() {
        @Override
        public void updateInputs(GyroIOInputs inputs, double acquisitionTimestampSeconds) {
          int samples =
              (int)
                  Math.round(
                      org.littletonrobotics.junction.LoggedRobot.defaultPeriodSecs
                          * GlobalConstants.ODOMETRY_FREQUENCY);
          if (samples < 1 || samples > 64)
            throw new IllegalStateException("Fixture sample count outside bounded configuration");
          double stepSeconds = 1.0 / GlobalConstants.ODOMETRY_FREQUENCY;
          double radius = SwerveConstants.getWheelRadiusMeters();
          for (int i = 0; i < 4; i++) {
            FixtureModule module = modules[i];
            module.measuredVelocity = DriverStation.isEnabled() ? module.requestedVelocity : 0;
            states[i] = new SwerveModuleState(module.measuredVelocity * radius, module.angle);
            module.timestamps = new double[samples];
            module.positions = new double[samples];
            module.angles = new Rotation2d[samples];
          }
          double omega = kinematics.toChassisSpeeds(states).omegaRadiansPerSecond;
          inputs.odometryYawTimestamps = new double[samples];
          inputs.odometryYawPositions = new Rotation2d[samples];
          for (int sample = 0; sample < samples; sample++) {
            double timestamp = acquisitionTimestampSeconds - (samples - 1 - sample) * stepSeconds;
            yaw = yaw.plus(Rotation2d.fromRadians(omega * stepSeconds));
            inputs.odometryYawTimestamps[sample] = timestamp;
            inputs.odometryYawPositions[sample] = yaw;
            for (FixtureModule module : modules) {
              module.position += module.measuredVelocity * stepSeconds;
              module.timestamps[sample] = timestamp;
              module.positions[sample] = module.position;
              module.angles[sample] = module.angle;
            }
          }
          inputs.connected = true;
          inputs.yawPosition = yaw;
          inputs.yawVelocityRadPerSec = omega;
        }

        @Override
        public void resetYaw(double degrees) {
          yaw = Rotation2d.fromDegrees(degrees);
        }
      };

  DesktopFixtureIO() {
    for (int i = 0; i < 4; i++) modules[i] = new FixtureModule();
  }

  static RobotContainer createContainer() {
    DesktopFixtureIO fixture = new DesktopFixtureIO();
    return new RobotContainer(fixture.gyro, fixture.modules);
  }

  GyroIO gyro() {
    return gyro;
  }

  ModuleIO module(int index) {
    return modules[index];
  }

  private static final class FixtureModule implements ModuleIO {
    private double requestedVelocity;
    private double measuredVelocity;
    private double position;
    private double feedforward;
    private Rotation2d angle = Rotation2d.kZero;
    private double[] timestamps = {};
    private double[] positions = {};
    private Rotation2d[] angles = {};

    @Override
    public void updateInputs(ModuleIOInputs inputs, double acquisitionTimestampSeconds) {
      inputs.driveConnected = true;
      inputs.turnConnected = true;
      inputs.drivePositionRad = position;
      inputs.driveVelocityRadPerSec = measuredVelocity;
      // This is the real command's feedforward request, NOT a simulated or measured terminal
      // voltage.
      inputs.driveAppliedVolts = DriverStation.isEnabled() ? feedforward : 0;
      inputs.turnPosition = angle;
      inputs.turnAbsolutePosition = angle;
      inputs.turnPositionRotations = angle.getRotations();
      inputs.turnAbsolutePositionRotations = angle.getRotations();
      inputs.odometryTimestamps = timestamps.clone();
      inputs.odometryDrivePositionsRad = positions.clone();
      inputs.odometryTurnPositions = angles.clone();
      inputs.odometryTurnPositionsRotations = new double[angles.length];
      for (int i = 0; i < angles.length; i++)
        inputs.odometryTurnPositionsRotations[i] = angles[i].getRotations();
    }

    @Override
    public void setDriveVelocity(double velocity, double requestedFeedforward) {
      requestedVelocity = velocity;
      feedforward = requestedFeedforward;
    }

    @Override
    public void setDriveOpenLoop(double output) {
      if (output != 0)
        throw new IllegalStateException(
            "Deterministic fixture supports normal velocity control and stop only");
      requestedVelocity = 0;
      feedforward = 0;
    }

    @Override
    public void setTurnOpenLoop(double output) {
      if (output != 0)
        throw new IllegalStateException(
            "Deterministic fixture does not model characterization voltage");
    }

    @Override
    public void setTurnPosition(Rotation2d position) {
      angle = position;
    }
  }
}
