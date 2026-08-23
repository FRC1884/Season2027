package org.Griffins1884.frc2027.subsystems.swerve;

import com.ctre.phoenix6.hardware.TalonFX;
import edu.wpi.first.math.geometry.Rotation2d;
import java.util.List;

public interface ModuleIO {
  class ModuleIOInputs {
    public boolean driveConnected = false;
    public double drivePositionRad = 0.0;
    public double driveVelocityRadPerSec = 0.0;
    public double driveAppliedVolts = 0.0;
    public double driveCurrentAmps = 0.0;
    public double terrainDriveAuthorityScale = 1.0;

    public boolean turnConnected = false;
    public Rotation2d turnPosition = new Rotation2d();
    public double turnPositionRotations = 0.0;
    public Rotation2d turnAbsolutePosition = new Rotation2d();
    public double turnAbsolutePositionRotations = 0.0;
    public double turnZeroTrimRotations = 0.0;
    public double turnVelocityRadPerSec = 0.0;
    public double turnAppliedVolts = 0.0;
    public double turnCurrentAmps = 0.0;
    public double terrainTurnAuthorityScale = 1.0;

    public double[] odometryTimestamps = new double[] {};
    public double[] odometryDrivePositionsRad = new double[] {};
    public Rotation2d[] odometryTurnPositions = new Rotation2d[] {};
    public double[] odometryTurnPositionsRotations = new double[] {};
  }

  default void updateInputs(ModuleIOInputs inputs) {}

  default void setDriveOpenLoop(double output) {}

  default void setTurnOpenLoop(double output) {}

  default void setDriveVelocity(double velocityRadPerSec) {}

  default void setDriveVelocity(double velocityRadPerSec, double feedforward) {
    setDriveVelocity(velocityRadPerSec);
  }

  default void setTurnPosition(Rotation2d rotation) {}

  default void setDrivePID(double kP, double kI, double kD) {}

  default void setTurnPID(double kP, double kI, double kD) {}

  default void setBrakeMode(boolean enabled) {}

  default void addOrchestraInstruments(List<TalonFX> instruments) {}

  default void captureZeroTrim() {}

  default void clearZeroTrim() {}
}
