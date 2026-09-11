package org.Griffins1884.frc2027.subsystems.leds;

import edu.wpi.first.wpilibj.motorcontrol.Spark;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.function.Supplier;

public class LEDSubsystem extends SubsystemBase {
  private Spark blinkin;

  public LEDSubsystem() {
    this.blinkin = new Spark(1);
  }

  /*
   * Set the color and blink pattern of the LED strip.
   *
   * Consult the Rev Robotics Blinkin manual Table 5 for a mapping of values to
   * patterns.
   *
   * @param val The LED blink color and patern value [-1,1]
   *
   */
  public Command set(double val) {
    return Commands.runOnce(
        () -> {
          if ((val >= -1.0) && (val <= 1.0))
            blinkin.set(val);
        },
        this);
  }

  public Command whiteFlash() {
    return set(0.25);
  }

  public Command rainbow() {
    return set(-0.15);
  }

  public Command chase_red() {
    return set(-0.15);
  }

  public Command chase_blue() {
    return set(-0.15);
  }

  public Command allianceColor(Supplier<Boolean> isRed) {
    if (isRed.get() == true)
      return chase_red();
    else
      return chase_blue();
  }
}
