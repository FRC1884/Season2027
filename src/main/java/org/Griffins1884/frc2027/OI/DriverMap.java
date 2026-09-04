package org.Griffins1884.frc2027.OI;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.util.function.DoubleSupplier;

/** Reusable driver-input abstraction carried forward from Season2026 without mechanism bindings. */
public interface DriverMap {
  DoubleSupplier getXAxis();

  DoubleSupplier getYAxis();

  DoubleSupplier getRotAxis();

  Trigger resetOdometry();

  default Trigger leftBackButton() {
    return new Trigger(() -> false);
  }

  default Trigger rightBackButton() {
    return new Trigger(() -> false);
  }

  default Command rumble() {
    return Commands.none();
  }
}
