package org.Griffins1884.frc2027.util.swerve;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.Griffins1884.frc2027.util.EqualsUtil;
import org.Griffins1884.frc2027.util.GeomUtil;

/**
 * "Inspired" by FRC team 254. See the license file in the root directory of the original project.
 *
 * <p>Takes a prior setpoint, a desired setpoint, and outputs a new setpoint that respects module
 * velocity, acceleration, and steering constraints.
 */
public class SwerveSetpointGenerator {
  private final SwerveDriveKinematics kinematics;
  private final Translation2d[] moduleLocations;

  public SwerveSetpointGenerator(
      SwerveDriveKinematics kinematics, Translation2d[] moduleLocations) {
    this.kinematics = kinematics;
    this.moduleLocations = moduleLocations;
  }

  private boolean flipHeading(Rotation2d prevToGoal) {
    return Math.abs(prevToGoal.getRadians()) > Math.PI / 2.0;
  }

  private double unwrapAngle(double ref, double angle) {
    double diff = angle - ref;
    if (diff > Math.PI) {
      return angle - 2.0 * Math.PI;
    } else if (diff < -Math.PI) {
      return angle + 2.0 * Math.PI;
    } else {
      return angle;
    }
  }

  @FunctionalInterface
  private interface Function2d {
    double f(double x, double y);
  }

  private double findRoot(
      Function2d func,
      double x0,
      double y0,
      double f0,
      double x1,
      double y1,
      double f1,
      int iterationsLeft) {
    if (iterationsLeft < 0 || EqualsUtil.epsilonEquals(f0, f1)) {
      return 1.0;
    }
    double sGuess = Math.max(0.0, Math.min(1.0, -f0 / (f1 - f0)));
    double xGuess = (x1 - x0) * sGuess + x0;
    double yGuess = (y1 - y0) * sGuess + y0;
    double fGuess = func.f(xGuess, yGuess);
    if (Math.signum(f0) == Math.signum(fGuess)) {
      return sGuess
          + (1.0 - sGuess) * findRoot(func, xGuess, yGuess, fGuess, x1, y1, f1, iterationsLeft - 1);
    } else {
      return sGuess * findRoot(func, x0, y0, f0, xGuess, yGuess, fGuess, iterationsLeft - 1);
    }
  }

  protected double findSteeringMaxS(
      double x0,
      double y0,
      double f0,
      double x1,
      double y1,
      double f1,
      double maxDeviation,
      int maxIterations) {
    f1 = unwrapAngle(f0, f1);
    double diff = f1 - f0;
    if (Math.abs(diff) <= maxDeviation) {
      return 1.0;
    }
    double offset = f0 + Math.signum(diff) * maxDeviation;
    Function2d func = (x, y) -> unwrapAngle(f0, Math.atan2(y, x)) - offset;
    return findRoot(func, x0, y0, f0 - offset, x1, y1, f1 - offset, maxIterations);
  }

  protected double findDriveMaxS(
      double x0,
      double y0,
      double f0,
      double x1,
      double y1,
      double f1,
      double maxVelStep,
      int maxIterations) {
    double diff = f1 - f0;
    if (Math.abs(diff) <= maxVelStep) {
      return 1.0;
    }
    double offset = f0 + Math.signum(diff) * maxVelStep;
    Function2d func = (x, y) -> Math.hypot(x, y) - offset;
    return findRoot(func, x0, y0, f0 - offset, x1, y1, f1 - offset, maxIterations);
  }

  public SwerveSetpoint generateSetpoint(
      ModuleLimits limits, SwerveSetpoint prevSetpoint, ChassisSpeeds desiredState, double dt) {
    Translation2d[] modules = moduleLocations;

    SwerveModuleState[] desiredModuleState = kinematics.toSwerveModuleStates(desiredState);
    if (limits.maxDriveVelocity() > 0.0) {
      SwerveDriveKinematics.desaturateWheelSpeeds(desiredModuleState, limits.maxDriveVelocity());
      desiredState = kinematics.toChassisSpeeds(desiredModuleState);
    }

    boolean needToSteer = true;
    if (EqualsUtil.GeomExtensions.epsilonEquals(GeomUtil.toTwist2d(desiredState), new Twist2d())) {
      needToSteer = false;
      for (int i = 0; i < modules.length; ++i) {
        desiredModuleState[i].angle = prevSetpoint.moduleStates()[i].angle;
        desiredModuleState[i].speedMetersPerSecond = 0.0;
      }
    }

    double[] prevVx = new double[modules.length];
    double[] prevVy = new double[modules.length];
    Rotation2d[] prevHeading = new Rotation2d[modules.length];
    double[] desiredVx = new double[modules.length];
    double[] desiredVy = new double[modules.length];
    Rotation2d[] desiredHeading = new Rotation2d[modules.length];
    boolean allModulesShouldFlip = true;
    for (int i = 0; i < modules.length; ++i) {
      prevVx[i] =
          prevSetpoint.moduleStates()[i].angle.getCos()
              * prevSetpoint.moduleStates()[i].speedMetersPerSecond;
      prevVy[i] =
          prevSetpoint.moduleStates()[i].angle.getSin()
              * prevSetpoint.moduleStates()[i].speedMetersPerSecond;
      prevHeading[i] = prevSetpoint.moduleStates()[i].angle;
      if (prevSetpoint.moduleStates()[i].speedMetersPerSecond < 0.0) {
        prevHeading[i] = prevHeading[i].rotateBy(Rotation2d.fromRadians(Math.PI));
      }
      desiredVx[i] =
          desiredModuleState[i].angle.getCos() * desiredModuleState[i].speedMetersPerSecond;
      desiredVy[i] =
          desiredModuleState[i].angle.getSin() * desiredModuleState[i].speedMetersPerSecond;
      desiredHeading[i] = desiredModuleState[i].angle;
      if (desiredModuleState[i].speedMetersPerSecond < 0.0) {
        desiredHeading[i] = desiredHeading[i].rotateBy(Rotation2d.fromRadians(Math.PI));
      }
      if (allModulesShouldFlip) {
        double requiredRotationRad =
            Math.abs(prevHeading[i].unaryMinus().rotateBy(desiredHeading[i]).getRadians());
        if (requiredRotationRad < Math.PI / 2.0) {
          allModulesShouldFlip = false;
        }
      }
    }

    if (allModulesShouldFlip
        && !EqualsUtil.GeomExtensions.epsilonEquals(
            GeomUtil.toTwist2d(prevSetpoint.chassisSpeeds()), new Twist2d())
        && !EqualsUtil.GeomExtensions.epsilonEquals(
            GeomUtil.toTwist2d(desiredState), new Twist2d())) {
      return generateSetpoint(limits, prevSetpoint, new ChassisSpeeds(), dt);
    }

    double dx = desiredState.vxMetersPerSecond - prevSetpoint.chassisSpeeds().vxMetersPerSecond;
    double dy = desiredState.vyMetersPerSecond - prevSetpoint.chassisSpeeds().vyMetersPerSecond;
    double dtheta =
        desiredState.omegaRadiansPerSecond - prevSetpoint.chassisSpeeds().omegaRadiansPerSecond;

    double minS = 1.0;
    List<Optional<Rotation2d>> overrideSteering = new ArrayList<>(modules.length);
    double maxThetaStep = dt * limits.maxSteeringVelocity();
    for (int i = 0; i < modules.length; ++i) {
      if (!needToSteer) {
        overrideSteering.add(Optional.of(prevSetpoint.moduleStates()[i].angle));
        continue;
      }
      overrideSteering.add(Optional.empty());
      if (EqualsUtil.epsilonEquals(prevSetpoint.moduleStates()[i].speedMetersPerSecond, 0.0)) {
        if (EqualsUtil.epsilonEquals(desiredModuleState[i].speedMetersPerSecond, 0.0)) {
          overrideSteering.set(i, Optional.of(prevSetpoint.moduleStates()[i].angle));
          continue;
        }

        Rotation2d necessaryRotation =
            prevSetpoint.moduleStates()[i].angle.unaryMinus().rotateBy(desiredModuleState[i].angle);
        if (flipHeading(necessaryRotation)) {
          necessaryRotation = necessaryRotation.rotateBy(Rotation2d.fromRadians(Math.PI));
        }
        double numStepsNeeded = Math.abs(necessaryRotation.getRadians()) / maxThetaStep;

        if (numStepsNeeded <= 1.0) {
          overrideSteering.set(i, Optional.of(desiredModuleState[i].angle));
          continue;
        } else {
          overrideSteering.set(
              i,
              Optional.of(
                  prevSetpoint.moduleStates()[i].angle.rotateBy(
                      Rotation2d.fromRadians(
                          Math.signum(necessaryRotation.getRadians()) * maxThetaStep))));
          minS = 0.0;
          continue;
        }
      }
      if (minS == 0.0) {
        continue;
      }

      double s =
          findSteeringMaxS(
              prevVx[i],
              prevVy[i],
              prevHeading[i].getRadians(),
              desiredVx[i],
              desiredVy[i],
              desiredHeading[i].getRadians(),
              maxThetaStep,
              8);
      minS = Math.min(minS, s);
    }

    double maxVelStep = dt * limits.maxDriveAcceleration();
    for (int i = 0; i < modules.length; ++i) {
      if (minS == 0.0) {
        break;
      }
      double vxMinS = minS == 1.0 ? desiredVx[i] : (desiredVx[i] - prevVx[i]) * minS + prevVx[i];
      double vyMinS = minS == 1.0 ? desiredVy[i] : (desiredVy[i] - prevVy[i]) * minS + prevVy[i];
      double s =
          minS
              * findDriveMaxS(
                  prevVx[i],
                  prevVy[i],
                  Math.hypot(prevVx[i], prevVy[i]),
                  vxMinS,
                  vyMinS,
                  Math.hypot(vxMinS, vyMinS),
                  maxVelStep,
                  10);
      minS = Math.min(minS, s);
    }

    ChassisSpeeds retSpeeds =
        new ChassisSpeeds(
            prevSetpoint.chassisSpeeds().vxMetersPerSecond + minS * dx,
            prevSetpoint.chassisSpeeds().vyMetersPerSecond + minS * dy,
            prevSetpoint.chassisSpeeds().omegaRadiansPerSecond + minS * dtheta);
    SwerveModuleState[] retStates = kinematics.toSwerveModuleStates(retSpeeds);
    for (int i = 0; i < modules.length; ++i) {
      Optional<Rotation2d> maybeOverride = overrideSteering.get(i);
      if (maybeOverride.isPresent()) {
        Rotation2d override = maybeOverride.get();
        if (flipHeading(retStates[i].angle.unaryMinus().rotateBy(override))) {
          retStates[i].speedMetersPerSecond *= -1.0;
        }
        retStates[i].angle = override;
      }
      Rotation2d deltaRotation =
          prevSetpoint.moduleStates()[i].angle.unaryMinus().rotateBy(retStates[i].angle);
      if (flipHeading(deltaRotation)) {
        retStates[i].angle = retStates[i].angle.rotateBy(Rotation2d.fromRadians(Math.PI));
        retStates[i].speedMetersPerSecond *= -1.0;
      }
    }
    return new SwerveSetpoint(retSpeeds, retStates);
  }
}
