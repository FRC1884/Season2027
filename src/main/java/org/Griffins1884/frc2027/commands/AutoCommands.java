package org.Griffins1884.frc2027.commands;

import com.pathplanner.lib.auto.NamedCommands;
import edu.wpi.first.wpilibj2.command.Commands;
import org.Griffins1884.frc2027.subsystems.Superstructure;
import org.Griffins1884.frc2027.subsystems.leds.LEDSubsystem;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;

public class AutoCommands {
    private AutoCommands() {
    }

    public static void registerAutoCommands(
            Superstructure superstructure, SwerveSubsystem drive, LEDSubsystem leds) {

        NamedCommands.registerCommand(
                "GetRShoot",
                Commands.sequence(superstructure.setSuperStateCmd(Superstructure.SuperState.SHOOTING)));

        NamedCommands.registerCommand("AlignToHP", DriveCommands.alignToHPd(drive));

        NamedCommands.registerCommand("AlignToDepot", DriveCommands.alignToDepot(drive));

        NamedCommands.registerCommand("Shoot", Commands.runOnce(superstructure::toggleShootEnabled));

        NamedCommands.registerCommand(
                "Idling",
                Commands.sequence(superstructure.setSuperStateCmd(Superstructure.SuperState.IDLING)));

        NamedCommands.registerCommand(
                "Intake", superstructure.setSuperStateCmd(Superstructure.SuperState.INTAKING));

        NamedCommands.registerCommand(
                "ShootIntake", superstructure.setSuperStateCmd(Superstructure.SuperState.SHOOT_INTAKE));

        // NamedCommands.registerCommand("WhiteFlashLEDs", leds.whiteFlash());

        // NamedCommands.registerCommand("RainbowLEDs", leds.rainbow());
    }
}
