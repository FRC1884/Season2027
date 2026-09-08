package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DesktopWorkloadChecksTest {
  private DesktopMatchMain.PhaseEvidence valid() {
    var e = new DesktopMatchMain.PhaseEvidence();
    e.loops = 10;
    e.correctModeLoops = 10;
    e.readyLoops = 10;
    e.enabledLoops = 10;
    e.acquiredSamples = 50;
    e.consumedSamples = 50;
    e.appliedDriveRequests = 10;
    e.nonzeroInputLoops = 10;
    e.driverCommandExecutions = 10;
    e.autonomousCommandExecutions = 10;
    e.maxDesiredSpeed = 1;
    e.maxDriveVoltage = 2;
    e.maxMeasuredSpeed = .8;
    e.distanceMeters = .1;
    return e;
  }

  @Test
  void idealFixtureRequiresVelocityCommandsInsteadOfUnmodelledTerminalVoltage() {
    var e = valid();
    e.maxDriveVoltage = 0;
    assertTrue(DesktopWorkloadChecks.failures("NORMAL", "teleop", e, false).isEmpty());
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "teleop", e, true).isEmpty());
    e.appliedDriveRequests = 0;
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "teleop", e, false).isEmpty());
  }

  @Test
  void eachPhaseRequiresItsOwnEvidence() {
    assertTrue(DesktopWorkloadChecks.failures("NORMAL", "warmup", valid()).isEmpty());
    var teleop = valid();
    teleop.enabledLoops = 0;
    teleop.correctModeLoops = 0;
    teleop.appliedDriveRequests = 0;
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "teleop", teleop).isEmpty());
  }

  @Test
  void emptyAutonomousCannotUseDefaultCommandAsProof() {
    var auto = valid();
    auto.autonomousCommandExecutions = 0;
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "auto_fixture", auto).isEmpty());
  }

  @Test
  void missingSensorsOrFrozenPoseFailDespiteRunningCommands() {
    var e = valid();
    e.consumedSamples = 0;
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "teleop", e).isEmpty());
    e = valid();
    e.distanceMeters = 0;
    assertFalse(DesktopWorkloadChecks.failures("NORMAL", "teleop", e).isEmpty());
  }

  @Test
  void idleRequiresSensorsAndActualDisabledModeButNotMovement() {
    var e = valid();
    e.enabledLoops = 0;
    e.driverCommandExecutions = 0;
    e.maxDriveVoltage = 0;
    e.distanceMeters = 0;
    assertTrue(DesktopWorkloadChecks.failures("IDLE", "teleop", e).isEmpty());
    e.correctModeLoops = 0;
    assertFalse(DesktopWorkloadChecks.failures("IDLE", "teleop", e).isEmpty());
  }
}
