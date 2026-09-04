package org.Griffins1884.frc2027.OI;

import edu.wpi.first.wpilibj2.command.button.Trigger;

public final class PS5ProDriverMap extends PS5DriverMap {
  private static final int LEFT_BACK_BUTTON = 15;
  private static final int RIGHT_BACK_BUTTON = 16;

  public PS5ProDriverMap(int port) {
    super(port);
  }

  @Override
  public Trigger leftBackButton() {
    return button(LEFT_BACK_BUTTON);
  }

  @Override
  public Trigger rightBackButton() {
    return button(RIGHT_BACK_BUTTON);
  }
}
