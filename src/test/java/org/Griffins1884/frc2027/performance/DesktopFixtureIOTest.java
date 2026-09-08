package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import org.Griffins1884.frc2027.subsystems.swerve.GyroIO;
import org.Griffins1884.frc2027.subsystems.swerve.ModuleIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DesktopFixtureIOTest {
  @BeforeEach
  void enableOwnedSimulatedDs() {
    assertTrue(HAL.initialize(500, 0));
    assertFalse(RobotBase.isReal());
    DriverStationSim.resetData();
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setEnabled(true);
    DriverStationSim.notifyNewData();
  }

  @AfterEach
  void restoreSimulatedDs() {
    DriverStationSim.resetData();
    DriverStationSim.notifyNewData();
  }

  @Test
  void identicalCommandsProduceExactlyEqualAlignedSensorSequences() {
    var a = new DesktopFixtureIO();
    var b = new DesktopFixtureIO();
    for (int cycle = 0; cycle < 100; cycle++) {
      for (int module = 0; module < 4; module++) {
        double velocity = 10 * Math.sin(cycle * .13 + module);
        Rotation2d angle = Rotation2d.fromRadians(Math.sin(cycle * .02 + module));
        a.module(module).setDriveVelocity(velocity, 1);
        b.module(module).setDriveVelocity(velocity, 1);
        a.module(module).setTurnPosition(angle);
        b.module(module).setTurnPosition(angle);
      }
      var ga = new GyroIO.GyroIOInputs();
      var gb = new GyroIO.GyroIOInputs();
      a.gyro().updateInputs(ga, (cycle + 1) * .02);
      b.gyro().updateInputs(gb, (cycle + 1) * .02);
      assertEquals(ga.yawPosition, gb.yawPosition);
      assertArrayEquals(ga.odometryYawTimestamps, gb.odometryYawTimestamps);
      assertEquals(5, ga.odometryYawPositions.length);
      for (int module = 0; module < 4; module++) {
        var ma = new ModuleIO.ModuleIOInputs();
        var mb = new ModuleIO.ModuleIOInputs();
        a.module(module).updateInputs(ma, (cycle + 1) * .02);
        b.module(module).updateInputs(mb, (cycle + 1) * .02);
        assertArrayEquals(ma.odometryDrivePositionsRad, mb.odometryDrivePositionsRad);
        assertArrayEquals(ma.odometryTimestamps, ga.odometryYawTimestamps);
        assertArrayEquals(ma.odometryTurnPositions, mb.odometryTurnPositions);
      }
    }
  }

  @Test
  void disabledStateStopsFixtureAndUnsupportedActuationIsRejected() {
    var fixture = new DesktopFixtureIO();
    fixture.module(0).setDriveVelocity(10, 2);
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    fixture.gyro().updateInputs(new GyroIO.GyroIOInputs(), .02);
    var input = new ModuleIO.ModuleIOInputs();
    fixture.module(0).updateInputs(input, .02);
    assertEquals(0, input.driveVelocityRadPerSec);
    assertEquals(0, input.driveAppliedVolts);
    assertThrows(IllegalStateException.class, () -> fixture.module(0).setDriveOpenLoop(2));
    assertThrows(IllegalStateException.class, () -> fixture.module(0).setTurnOpenLoop(2));
  }
}
