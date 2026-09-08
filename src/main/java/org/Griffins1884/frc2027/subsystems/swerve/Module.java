package org.Griffins1884.frc2027.subsystems.swerve;

import static org.Griffins1884.frc2027.subsystems.swerve.SwerveConstants.*;

import com.ctre.phoenix6.hardware.TalonFX;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.wpilibj.Alert;
import edu.wpi.first.wpilibj.Alert.AlertType;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Timer;
import java.util.List;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.util.LoggedTunableNumber;
import org.littletonrobotics.junction.Logger;

public class Module {
  private static final double ANGLE_JUMP_THRESHOLD_RAD = Math.toRadians(35.0);
  private static final double ANGLE_JUMP_MAX_TURN_RATE_RAD_PER_SEC = 1.0;
  private static final double SPEED_RATIO_EPSILON_MPS = 0.15;
  private static final LoggedTunableNumber krakenDrivekS =
      new LoggedTunableNumber("Drive/Module/DrivekS");
  private static final LoggedTunableNumber krakenDrivekV =
      new LoggedTunableNumber("Drive/Module/DrivekV");
  private static final LoggedTunableNumber krakenDrivekT =
      new LoggedTunableNumber("Drive/Module/DrivekT");
  private static final LoggedTunableNumber krakenDrivekP =
      new LoggedTunableNumber("Drive/Module/DrivekP");
  private static final LoggedTunableNumber krakenDrivekD =
      new LoggedTunableNumber("Drive/Module/DrivekD");
  private static final LoggedTunableNumber krakenTurnkP =
      new LoggedTunableNumber("Drive/Module/TurnkP");
  private static final LoggedTunableNumber krakenTurnkD =
      new LoggedTunableNumber("Drive/Module/TurnkD");

  static {
    krakenDrivekS.initDefault(KRAKEN_DRIVE_TORQUE_GAINS.kS().get());
    krakenDrivekV.initDefault(KRAKEN_DRIVE_TORQUE_GAINS.kV().get());
    krakenDrivekT.initDefault(
        SwerveConstants.KRAKEN_DRIVE_GEAR_RATIO / DCMotor.getKrakenX60Foc(1).KtNMPerAmp);
    krakenDrivekP.initDefault(KRAKEN_DRIVE_TORQUE_GAINS.kP().get());
    krakenDrivekD.initDefault(KRAKEN_DRIVE_TORQUE_GAINS.kD().get());
    krakenTurnkP.initDefault(KRAKEN_TURN_TORQUE_GAINS.kP().get());
    krakenTurnkD.initDefault(KRAKEN_TURN_TORQUE_GAINS.kD().get());
  }

  private final ModuleIO io;
  private final ModuleIO.ModuleIOInputs inputs = new ModuleIO.ModuleIOInputs();
  private final int index;
  private final String keyInputsDriveConnected;
  private final String keyInputsDrivePositionRad;
  private final String keyInputsDriveVelocityRadPerSec;
  private final String keyInputsDriveAppliedVolts;
  private final String keyInputsDriveCurrentAmps;
  private final String keyInputsTurnConnected;
  private final String keyInputsTurnPosition;
  private final String keyInputsTurnVelocityRadPerSec;
  private final String keyInputsTurnAppliedVolts;
  private final String keyInputsTurnCurrentAmps;
  private final String keyAngleJumpDetected;
  private final String keyAngleJumpCount;
  private final String keyZeroTrimRotations;
  private final String keyInputsOdometryTimestamps;
  private final String keyInputsOdometryDrivePositionsRad;
  private final String keyInputsOdometryTurnPositions;
  private final String keyInputsOdometryTurnPositionsRotations;
  private final String keyDesiredSpeedMps;
  private final String keyActualSpeedMps;
  private final String keySpeedErrorMps;
  private final String keySpeedRatio;
  private final String keyDesiredAngleRad;
  private final String keyActualAngleRad;
  private final String keyAbsoluteAngleRad;
  private final String keyAngleErrorRad;
  private final String keyLastAngleDeltaRad;
  private final String keyInvalidOdometryBatches;

  private SimpleMotorFeedforward krakenFfModel =
      new SimpleMotorFeedforward(krakenDrivekS.get(), krakenDrivekV.get());

  private final Alert driveDisconnectedAlert;
  private final Alert turnDisconnectedAlert;
  private double desiredSpeedMetersPerSec = 0.0;
  private Rotation2d desiredAngle = new Rotation2d();
  private Rotation2d lastTurnPosition = new Rotation2d();
  private boolean angleJumpDetected = false;
  private int angleJumpCount = 0;
  private double lastAngleDeltaRad = 0.0;

  private double wheelRadiusMeters = SwerveConstants.getWheelRadiusMeters();
  private ModuleConfiguration desiredConfiguration;
  private ModuleConfiguration requestedConfiguration;
  private double desiredKs = krakenDrivekS.get();
  private double desiredKv = krakenDrivekV.get();
  private double appliedKs = desiredKs;
  private double appliedKv = desiredKv;
  private double lastLoggedTrim = Double.NaN;
  private int invalidOdometryBatches;
  private ModuleConfigurationWorker.Status lastConfigurationStatus;
  private final String configurationDesiredRevisionKey;
  private final String configurationAppliedRevisionKey;
  private final String configurationInFlightKey;
  private final String configurationFailedKey;
  private final String configurationInhibitedKey;
  private final String configurationAttemptsKey;
  private final String configurationCompletionsKey;
  private final String configurationDurationKey;
  private final String configurationErrorKey;
  private final String configurationDesiredGainsKey;
  private final String appliedFeedforwardKey;
  private double loggedAppliedKs = Double.NaN;
  private double loggedAppliedKv = Double.NaN;
  private final String configurationFailureCountKey;
  private final String configurationLastFailedRevisionKey;
  private final String configurationLastFailureErrorKey;
  private final String telemetryTimingKey;
  private final String odometryTimingKey;
  private ModuleConfiguration lastLoggedDesiredConfiguration;

  private SwerveModulePosition[] odometryPositions = new SwerveModulePosition[] {};

  public Module(ModuleIO io, int index) {
    this.io = io;
    this.index = index;
    telemetryTimingKey = "Swerve/Module" + index + "/Performance/TelemetryAndFaultEvaluationMS";
    odometryTimingKey = "Swerve/Module" + index + "/Performance/OdometryConversionMS";
    String configurationKey = "Swerve/Module" + index + "/Configuration";
    configurationDesiredRevisionKey = configurationKey + "/DesiredRevision";
    configurationAppliedRevisionKey = configurationKey + "/AppliedRevision";
    configurationInFlightKey = configurationKey + "/InFlight";
    configurationFailedKey = configurationKey + "/Failed";
    configurationInhibitedKey = configurationKey + "/Inhibited";
    configurationAttemptsKey = configurationKey + "/Attempts";
    configurationCompletionsKey = configurationKey + "/Completions";
    configurationDurationKey = configurationKey + "/LastDurationMS";
    configurationErrorKey = configurationKey + "/Error";
    configurationDesiredGainsKey = configurationKey + "/DesiredGains";
    appliedFeedforwardKey = configurationKey + "/AppliedFeedforward";
    configurationFailureCountKey = configurationKey + "/FailureCount";
    configurationLastFailedRevisionKey = configurationKey + "/LastFailedRevision";
    configurationLastFailureErrorKey = configurationKey + "/LastFailureError";
    keyInputsDriveConnected = "Swerve/Module" + index + "/Inputs/DriveConnected";
    keyInputsDrivePositionRad = "Swerve/Module" + index + "/Inputs/DrivePositionRad";
    keyInputsDriveVelocityRadPerSec = "Swerve/Module" + index + "/Inputs/DriveVelocityRadPerSec";
    keyInputsDriveAppliedVolts = "Swerve/Module" + index + "/Inputs/DriveAppliedVolts";
    keyInputsDriveCurrentAmps = "Swerve/Module" + index + "/Inputs/DriveCurrentAmps";
    keyInputsTurnConnected = "Swerve/Module" + index + "/Inputs/TurnConnected";
    keyInputsTurnPosition = "Swerve/Module" + index + "/Inputs/TurnPosition";
    keyInputsTurnVelocityRadPerSec = "Swerve/Module" + index + "/Inputs/TurnVelocityRadPerSec";
    keyInputsTurnAppliedVolts = "Swerve/Module" + index + "/Inputs/TurnAppliedVolts";
    keyInputsTurnCurrentAmps = "Swerve/Module" + index + "/Inputs/TurnCurrentAmps";
    keyAngleJumpDetected = "Swerve/Module" + index + "/AngleJumpDetected";
    keyAngleJumpCount = "Swerve/Module" + index + "/AngleJumpCount";
    keyZeroTrimRotations = "Swerve/Module" + index + "/ZeroTrimRotations";
    keyInputsOdometryTimestamps = "Swerve/Module" + index + "/Inputs/OdometryTimestamps";
    keyInputsOdometryDrivePositionsRad =
        "Swerve/Module" + index + "/Inputs/OdometryDrivePositionsRad";
    keyInputsOdometryTurnPositions = "Swerve/Module" + index + "/Inputs/OdometryTurnPositions";
    keyInputsOdometryTurnPositionsRotations =
        "Swerve/Module" + index + "/Inputs/OdometryTurnPositionsRotations";
    keyDesiredSpeedMps = "Swerve/Module" + index + "/DesiredSpeedMps";
    keyActualSpeedMps = "Swerve/Module" + index + "/ActualSpeedMps";
    keySpeedErrorMps = "Swerve/Module" + index + "/SpeedErrorMps";
    keySpeedRatio = "Swerve/Module" + index + "/SpeedRatio";
    keyDesiredAngleRad = "Swerve/Module" + index + "/DesiredAngleRad";
    keyActualAngleRad = "Swerve/Module" + index + "/ActualAngleRad";
    keyAbsoluteAngleRad = "Swerve/Module" + index + "/AbsoluteAngleRad";
    keyAngleErrorRad = "Swerve/Module" + index + "/AngleErrorRad";
    keyLastAngleDeltaRad = "Swerve/Module" + index + "/LastAngleDeltaRad";
    keyInvalidOdometryBatches = "Swerve/Module" + index + "/InvalidOdometryBatches";

    desiredConfiguration = readConfiguration();
    requestedConfiguration = desiredConfiguration;
    advanceGainChecks();
    io.initializeConfiguration(desiredConfiguration);
    driveDisconnectedAlert =
        new Alert("Disconnected drive motor on module " + index + ".", AlertType.kError);
    turnDisconnectedAlert =
        new Alert("Disconnected turn motor on module " + index + ".", AlertType.kError);
  }

  public void addOrchestraInstruments(List<TalonFX> instruments) {
    if (instruments == null) {
      return;
    }
    io.addOrchestraInstruments(instruments);
  }

  private ModuleConfiguration readConfiguration() {
    return new ModuleConfiguration(
        krakenDrivekP.get(), 0.0, krakenDrivekD.get(), krakenTurnkP.get(), 0.0, krakenTurnkD.get());
  }

  private boolean advanceGainChecks() {
    // Every stateful check must run, including on the first call.
    boolean changed = krakenDrivekS.hasChanged(hashCode());
    changed |= krakenDrivekV.hasChanged(hashCode());
    changed |= krakenDrivekP.hasChanged(hashCode());
    changed |= krakenDrivekD.hasChanged(hashCode());
    changed |= krakenTurnkP.hasChanged(hashCode());
    changed |= krakenTurnkD.hasChanged(hashCode());
    return changed;
  }

  void updateConfiguration(boolean disabled) {
    io.updateConfigurationState(disabled);
    if (RuntimeModeManager.allowsTuning(false)) {
      if (advanceGainChecks()) {
        desiredConfiguration = readConfiguration();
        desiredKs = krakenDrivekS.get();
        desiredKv = krakenDrivekV.get();
      }
      if (disabled
          && DriverStation.isDisabled()
          && !desiredConfiguration.equals(requestedConfiguration)) {
        if (io.requestConfiguration(desiredConfiguration))
          requestedConfiguration = desiredConfiguration;
      }
      if (disabled
          && DriverStation.isDisabled()
          && io.isConfigurationReady()
          && desiredConfiguration.equals(requestedConfiguration)
          && (appliedKs != desiredKs || appliedKv != desiredKv)) {
        krakenFfModel = new SimpleMotorFeedforward(desiredKs, desiredKv);
        appliedKs = desiredKs;
        appliedKv = desiredKv;
      }
    }
  }

  public void periodic() {
    updateConfiguration(DriverStation.isDisabled());
    captureInputs(SwerveConstants.getWheelRadiusMeters(), Timer.getFPGATimestamp());
    finishInputs(true);
  }

  /** Called for every module in one shared odometry critical section. No logging or tuning. */
  void captureInputs(double radiusMeters, double timestamp) {
    wheelRadiusMeters = radiusMeters;
    io.updateInputs(inputs, timestamp);
  }

  /** Main-thread calculations on the captured inputs, outside the odometry producer lock. */
  void finishInputs(boolean publishTelemetry) {
    long telemetryStarted = System.nanoTime();
    ModuleConfigurationWorker.Status status = io.getConfigurationStatus();
    if (!status.equals(lastConfigurationStatus)) {
      Logger.recordOutput(configurationDesiredRevisionKey, status.desiredRevision());
      Logger.recordOutput(configurationAppliedRevisionKey, status.appliedRevision());
      Logger.recordOutput(configurationInFlightKey, status.inFlight());
      Logger.recordOutput(configurationFailedKey, status.failed());
      Logger.recordOutput(configurationInhibitedKey, status.inhibited());
      Logger.recordOutput(configurationAttemptsKey, status.attempts());
      Logger.recordOutput(configurationCompletionsKey, status.completions());
      Logger.recordOutput(configurationDurationKey, status.lastDurationMs());
      Logger.recordOutput(configurationErrorKey, status.error());
      Logger.recordOutput(configurationFailureCountKey, status.failureCount());
      Logger.recordOutput(configurationLastFailedRevisionKey, status.lastFailedRevision());
      Logger.recordOutput(configurationLastFailureErrorKey, status.lastFailureError());
      lastConfigurationStatus = status;
    }
    if (!desiredConfiguration.equals(lastLoggedDesiredConfiguration)) {
      Logger.recordOutput(
          configurationDesiredGainsKey,
          new double[] {
            desiredConfiguration.driveP(),
            desiredConfiguration.driveI(),
            desiredConfiguration.driveD(),
            desiredConfiguration.turnP(),
            desiredConfiguration.turnI(),
            desiredConfiguration.turnD()
          });
      lastLoggedDesiredConfiguration = desiredConfiguration;
    }
    if (Double.compare(loggedAppliedKs, appliedKs) != 0
        || Double.compare(loggedAppliedKv, appliedKv) != 0) {
      Logger.recordOutput(appliedFeedforwardKey, new double[] {appliedKs, appliedKv});
      loggedAppliedKs = appliedKs;
      loggedAppliedKv = appliedKv;
    }
    Logger.recordOutput(keyInputsDriveConnected, inputs.driveConnected);
    Logger.recordOutput(keyInputsDrivePositionRad, inputs.drivePositionRad);
    Logger.recordOutput(keyInputsDriveVelocityRadPerSec, inputs.driveVelocityRadPerSec);
    Logger.recordOutput(keyInputsDriveAppliedVolts, inputs.driveAppliedVolts);
    Logger.recordOutput(keyInputsDriveCurrentAmps, inputs.driveCurrentAmps);
    Logger.recordOutput(keyInputsTurnConnected, inputs.turnConnected);
    Logger.recordOutput(keyInputsTurnPosition, inputs.turnPosition);
    Logger.recordOutput(keyInputsTurnVelocityRadPerSec, inputs.turnVelocityRadPerSec);
    Logger.recordOutput(keyInputsTurnAppliedVolts, inputs.turnAppliedVolts);
    Logger.recordOutput(keyInputsTurnCurrentAmps, inputs.turnCurrentAmps);

    lastAngleDeltaRad = MathUtil.angleModulus(getAngle().minus(lastTurnPosition).getRadians());
    boolean suspiciousJump =
        Math.abs(lastAngleDeltaRad) > ANGLE_JUMP_THRESHOLD_RAD
            && Math.abs(inputs.turnVelocityRadPerSec) < ANGLE_JUMP_MAX_TURN_RATE_RAD_PER_SEC;
    if (suspiciousJump) {
      angleJumpCount++;
    }
    angleJumpDetected = suspiciousJump;
    lastTurnPosition = getAngle();

    Logger.recordOutput(keyAngleJumpDetected, angleJumpDetected);
    Logger.recordOutput(keyAngleJumpCount, angleJumpCount);
    if (Double.compare(lastLoggedTrim, inputs.turnZeroTrimRotations) != 0) {
      Logger.recordOutput(keyZeroTrimRotations, inputs.turnZeroTrimRotations);
      lastLoggedTrim = inputs.turnZeroTrimRotations;
    }
    Logger.recordOutput(keyInputsOdometryTimestamps, inputs.odometryTimestamps);
    Logger.recordOutput(keyInputsOdometryDrivePositionsRad, inputs.odometryDrivePositionsRad);
    Logger.recordOutput(keyInputsOdometryTurnPositions, inputs.odometryTurnPositions);
    Logger.recordOutput(
        keyInputsOdometryTurnPositionsRotations, inputs.odometryTurnPositionsRotations);
    Logger.recordOutput(keyAbsoluteAngleRad, inputs.turnAbsolutePosition.getRadians());
    if (publishTelemetry) {
      double actualSpeedMetersPerSec = getVelocityMetersPerSec();
      double angleErrorRad = MathUtil.angleModulus(desiredAngle.minus(getAngle()).getRadians());
      double speedErrorMetersPerSec = desiredSpeedMetersPerSec - actualSpeedMetersPerSec;
      double speedRatio =
          Math.abs(desiredSpeedMetersPerSec) > SPEED_RATIO_EPSILON_MPS
              ? actualSpeedMetersPerSec / desiredSpeedMetersPerSec
              : 1.0;
      Logger.recordOutput(keyDesiredSpeedMps, desiredSpeedMetersPerSec);
      Logger.recordOutput(keyActualSpeedMps, actualSpeedMetersPerSec);
      Logger.recordOutput(keySpeedErrorMps, speedErrorMetersPerSec);
      Logger.recordOutput(keySpeedRatio, speedRatio);
      Logger.recordOutput(keyDesiredAngleRad, desiredAngle.getRadians());
      Logger.recordOutput(keyActualAngleRad, getAngle().getRadians());
      Logger.recordOutput(keyAngleErrorRad, angleErrorRad);
      Logger.recordOutput(keyLastAngleDeltaRad, lastAngleDeltaRad);
    }

    Logger.recordOutput(telemetryTimingKey, (System.nanoTime() - telemetryStarted) / 1e6);
    long odometryStarted = System.nanoTime();
    // Calculate positions for odometry
    int sampleCount = inputs.odometryTimestamps.length;
    if (inputs.odometryDrivePositionsRad.length != sampleCount
        || inputs.odometryTurnPositions.length != sampleCount
        || (inputs.odometryTurnPositionsRotations.length != 0
            && inputs.odometryTurnPositionsRotations.length != sampleCount)) {
      sampleCount = 0;
      invalidOdometryBatches++;
    }
    Logger.recordOutput(keyInvalidOdometryBatches, invalidOdometryBatches);
    odometryPositions = new SwerveModulePosition[sampleCount];
    for (int i = 0; i < sampleCount; i++) {
      double positionMeters =
          getCompensatedDrivePositionRad(inputs.odometryDrivePositionsRad[i], i)
              * getWheelRadiusMeters();
      Rotation2d angle = inputs.odometryTurnPositions[i];
      odometryPositions[i] = new SwerveModulePosition(positionMeters, angle);
    }

    Logger.recordOutput(odometryTimingKey, (System.nanoTime() - odometryStarted) / 1e6);
    // Update alerts
    driveDisconnectedAlert.set(!inputs.driveConnected);
    turnDisconnectedAlert.set(!inputs.turnConnected);
  }

  /** Runs the module with the specified setpoint state. Mutates the state to optimize it. */
  public void runSetpoint(SwerveModuleState state) {
    if (!io.isConfigurationReady()) {
      stop();
      return;
    }
    state.optimize(getAngle());
    state.cosineScale(getAngle());
    desiredSpeedMetersPerSec = state.speedMetersPerSecond;
    desiredAngle = state.angle;
    // Mechanical Advantage-style control for full Kraken modules
    double speedRadPerSec = state.speedMetersPerSecond / getWheelRadiusMeters();
    io.setDriveVelocity(speedRadPerSec, krakenFfModel.calculate(speedRadPerSec));
    if (Math.abs(state.angle.minus(getAngle()).getDegrees()) < TURN_DEADBAND_DEGREES) {
      io.setTurnOpenLoop(0.0);
    } else {
      io.setTurnPosition(state.angle);
    }
  }

  /** Runs the module with the specified output while controlling to zeroRotation degrees. */
  public void runCharacterization(double output) {
    if (!io.isConfigurationReady()) {
      stop();
      return;
    }
    desiredSpeedMetersPerSec = 0.0;
    desiredAngle = new Rotation2d();
    io.setDriveOpenLoop(output);
    io.setTurnPosition(new Rotation2d());
  }

  /** Runs a steer-only SysId sweep while keeping the drive stage disabled. */
  public void runTurnCharacterization(double output) {
    if (!io.isConfigurationReady()) {
      stop();
      return;
    }
    desiredSpeedMetersPerSec = 0.0;
    desiredAngle = new Rotation2d();
    io.setDriveOpenLoop(0.0);
    io.setTurnOpenLoop(output);
  }

  /** Disables all outputs to motors. */
  public void stop() {
    desiredSpeedMetersPerSec = 0.0;
    desiredAngle = getAngle();
    io.setDriveOpenLoop(0.0);
    io.setTurnOpenLoop(0.0);
  }

  /** Returns the current turn angle of the module. */
  public Rotation2d getAngle() {
    return inputs.turnPosition;
  }

  /** Returns the current drive position of the module in meters. */
  public double getPositionMeters() {
    return getCompensatedDrivePositionRad(inputs.drivePositionRad) * getWheelRadiusMeters();
  }

  /** Returns the current drive velocity of the module in meters per second. */
  public double getVelocityMetersPerSec() {
    return getCompensatedDriveVelocityRadPerSec(inputs.driveVelocityRadPerSec)
        * getWheelRadiusMeters();
  }

  /** Returns the module position (turn angle and drive position). */
  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(getPositionMeters(), getAngle());
  }

  /** Returns the module state (turn angle and drive velocity). */
  public SwerveModuleState getState() {
    return new SwerveModuleState(getVelocityMetersPerSec(), getAngle());
  }

  /** Returns the timestamps of the samples received this cycle. */
  public double[] getOdometryTimestamps() {
    return inputs.odometryTimestamps;
  }

  /** Returns the module positions received this cycle. */
  public SwerveModulePosition[] getOdometryPositions() {
    return odometryPositions;
  }

  /** Returns the module position in radians. */
  public double getWheelRadiusCharacterizationPosition() {
    return getCompensatedDrivePositionRad(inputs.drivePositionRad);
  }

  /** Returns the module velocity in rad/sec. */
  public double getFFCharacterizationVelocity() {
    return inputs.driveVelocityRadPerSec;
  }

  public double getVoltage() {
    return inputs.driveAppliedVolts;
  }

  public double getDriveVoltage() {
    return inputs.driveAppliedVolts;
  }

  public double getTurnVoltage() {
    return inputs.turnAppliedVolts;
  }

  public double getTurnPositionRad() {
    return inputs.turnPosition.getRadians();
  }

  public double getTurnVelocityRadPerSec() {
    return inputs.turnVelocityRadPerSec;
  }

  public double getTerrainDriveAuthorityScale() {
    return inputs.terrainDriveAuthorityScale;
  }

  public double getTerrainTurnAuthorityScale() {
    return inputs.terrainTurnAuthorityScale;
  }

  public int getIndex() {
    return index;
  }

  public double getDesiredSpeedMetersPerSec() {
    return desiredSpeedMetersPerSec;
  }

  public Rotation2d getDesiredAngle() {
    return desiredAngle;
  }

  public double getSpeedErrorMetersPerSec() {
    return desiredSpeedMetersPerSec - getVelocityMetersPerSec();
  }

  public double getSpeedRatio() {
    return Math.abs(desiredSpeedMetersPerSec) > SPEED_RATIO_EPSILON_MPS
        ? getVelocityMetersPerSec() / desiredSpeedMetersPerSec
        : 1.0;
  }

  public double getAngleErrorRad() {
    return MathUtil.angleModulus(desiredAngle.minus(getAngle()).getRadians());
  }

  public boolean isAngleJumpDetected() {
    return angleJumpDetected;
  }

  public int getAngleJumpCount() {
    return angleJumpCount;
  }

  public Rotation2d getAbsoluteAngle() {
    return inputs.turnAbsolutePosition;
  }

  public void captureZeroTrim() {
    io.captureZeroTrim();
  }

  public void clearZeroTrim() {
    io.clearZeroTrim();
  }

  public double getZeroTrimRotations() {
    return inputs.turnZeroTrimRotations;
  }

  boolean isConfigurationReady() {
    return io.isConfigurationReady();
  }

  void clearOdometrySamples() {
    io.clearOdometrySamples();
  }

  void refreshRadius(double radiusMeters) {
    wheelRadiusMeters = radiusMeters;
  }

  void close() {
    io.close();
  }

  private double getWheelRadiusMeters() {
    return wheelRadiusMeters;
  }

  private double getCompensatedDrivePositionRad(double rawDrivePositionRad) {
    return getCompensatedDrivePositionRad(rawDrivePositionRad, -1);
  }

  private double getCompensatedDrivePositionRad(double rawDrivePositionRad, int sampleIndex) {
    double steerPositionRotations =
        sampleIndex >= 0 && sampleIndex < inputs.odometryTurnPositionsRotations.length
            ? inputs.odometryTurnPositionsRotations[sampleIndex]
            : inputs.turnPositionRotations;
    return rawDrivePositionRad
        - steerPositionRotations
            * 2.0
            * Math.PI
            * SwerveConstants.getCouplingWheelRadiansPerSteerRadian();
  }

  private double getCompensatedDriveVelocityRadPerSec(double rawDriveVelocityRadPerSec) {
    return rawDriveVelocityRadPerSec
        - inputs.turnVelocityRadPerSec * SwerveConstants.getCouplingWheelRadiansPerSteerRadian();
  }
}
