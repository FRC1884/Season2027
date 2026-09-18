package org.Griffins1884.frc2027.subsystems.elevator;

import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.Objects;
import java.util.OptionalDouble;

/** Preset-height scaffold for two mechanically linked Kraken X60s, disconnected by default. */
public final class Elevator extends SubsystemBase {
  /**
   * A named height measured in meters from the verified elevator reference position.
   *
   * <p>Use {@code new Preset("loading")} until that position has been measured and reviewed. An
   * unset height is never interpreted as zero or as a homing request.
   */
  public record Preset(String name, OptionalDouble heightMeters) {
    public Preset {
      Objects.requireNonNull(name);
      Objects.requireNonNull(heightMeters);
      if (name.isBlank()) {
        throw new IllegalArgumentException("A preset needs a name");
      }
    }

    public Preset(String name) {
      this(name, OptionalDouble.empty());
    }
  }

  private final ElevatorIO io;

  public Elevator() {
    this(new ElevatorIOKrakenX60());
  }

  /** Accepts hardware or test IO and clears any previous request immediately. */
  public Elevator(ElevatorIO io) {
    this.io = Objects.requireNonNull(io);
    stop();
  }

  public boolean isReady() {
    return io.isReady();
  }

  /**
   * Requests and holds a configured preset while scheduled; ending or interruption stops both
   * motors. This command does not finish automatically at the target.
   *
   * <p>TODO: Define measured presets and operator bindings. Before connecting hardware, add this
   * subsystem to RobotContainer and run CommandScheduler in robotPeriodic. Stopping motor output
   * does not itself keep a gravity-loaded elevator from falling; review mechanical support and the
   * disabled/cancellation behavior before activation.
   */
  public Command goToPresetCommand(Preset preset) {
    Objects.requireNonNull(preset);
    return runEnd(() -> requestPreset(preset), this::stop)
        .withName("Elevator preset: " + preset.name());
  }

  private void requestPreset(Preset preset) {
    if (DriverStation.isDisabled() || !isReady() || preset.heightMeters().isEmpty()) {
      stop();
      return;
    }
    double heightMeters = preset.heightMeters().getAsDouble();
    if (!Double.isFinite(heightMeters) || !io.isHeightAllowed(heightMeters)) {
      stop();
      return;
    }
    io.setHeightMeters(heightMeters);
  }

  public void stop() {
    io.stop();
  }

  @Override
  public void periodic() {
    if (DriverStation.isDisabled() || !isReady()) {
      stop();
    }
  }
}
