package org.Griffins1884.frc2027.OI;

import static edu.wpi.first.wpilibj.GenericHID.RumbleType.kBothRumble;
import static edu.wpi.first.wpilibj2.command.Commands.startEnd;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.function.DoubleSupplier;

public class XboxDriverMap extends CommandXboxController implements DriverMap {
  public XboxDriverMap(int port) {
    super(port);
  }

  @Override
  public DoubleSupplier getXAxis() {
    return () -> -getLeftX();
  }

  @Override
  public DoubleSupplier getYAxis() {
    return () -> -getLeftY();
  }

  @Override
  public DoubleSupplier getRotAxis() {
    return () -> -getRightX();
  }

  @Override
  public Trigger resetOdometry() {
    return back();
  }

  @Override
  public Trigger robotRelativeOverride() {
    return leftTrigger();
  }

  @Override
  public Command rumble() {
    return startEnd(
        () -> getHID().setRumble(kBothRumble, 1.0), () -> getHID().setRumble(kBothRumble, 0.0));
  }
}
