package org.Griffins1884.frc2027.subsystems.swerve;

import static edu.wpi.first.units.Units.RadiansPerSecond;

import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.Timer;
import org.Griffins1884.frc2027.util.SparkUtil;
import org.ironmaple.simulation.drivesims.GyroSimulation;

public class GyroIOSim implements GyroIO {
  private final GyroSimulation gyroSimulation;

  public GyroIOSim(GyroSimulation gyroSimulation) {
    this.gyroSimulation = gyroSimulation;
  }

  @Override
  public void updateInputs(GyroIOInputs inputs) {
    updateInputs(inputs, Timer.getFPGATimestamp());
  }

  @Override
  public void updateInputs(GyroIOInputs inputs, double acquisitionTimestampSeconds) {
    inputs.connected = true;
    inputs.yawPosition = gyroSimulation.getGyroReading();
    inputs.yawVelocityRadPerSec =
        Units.degreesToRadians(gyroSimulation.getMeasuredAngularVelocity().in(RadiansPerSecond));
    inputs.odometryYawTimestamps =
        SparkUtil.getSimulationOdometryTimeStamps(acquisitionTimestampSeconds);
    inputs.odometryYawPositions = gyroSimulation.getCachedGyroReadings();
  }
}
