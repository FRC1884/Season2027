package org.Griffins1884.frc2027.subsystems.swerve;

/** Immutable desired motor gains; feedforward remains on the normal control path. */
public record ModuleConfiguration(
    double driveP, double driveI, double driveD, double turnP, double turnI, double turnD) {}
