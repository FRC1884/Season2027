package org.Griffins1884.frc2027.subsystems.intake;

import edu.wpi.first.math.system.plant.DCMotor;
import org.Griffins1884.frc2027.mechanisms.rollers.MechanismRollerIOSim;

public class IntakeIOSim extends MechanismRollerIOSim implements IntakeIO {
  public IntakeIOSim(DCMotor motorModel, double reduction, double moi) {
    super(motorModel, reduction, moi);
  }
}
