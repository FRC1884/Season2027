package org.Griffins1884.frc2027.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Per-instance phase obligations prevent a valid warm-up masking inhibited match phases. */
final class DesktopWorkloadChecks {
  private DesktopWorkloadChecks() {}

  static List<String> failures(
      String profile, String phase, DesktopMatchMain.PhaseEvidence evidence) {
    return failures(profile, phase, evidence, true);
  }

  static List<String> failures(
      String profile,
      String phase,
      DesktopMatchMain.PhaseEvidence evidence,
      boolean terminalVoltageModelled) {
    List<String> failures = new ArrayList<>();
    if (evidence.loops == 0) failures.add("no loops");
    if (evidence.correctModeLoops != evidence.loops)
      failures.add("actual DS mode differs from intended phase");
    if (evidence.readyLoops != evidence.loops) failures.add("configuration not ready");
    if (evidence.acquiredSamples == 0 || evidence.consumedSamples == 0)
      failures.add("missing sensor or odometry samples");
    boolean autonomous = Set.of("auto_fixture", "autonomous").contains(phase);
    boolean moving =
        !profile.startsWith("IDLE") && (autonomous || Set.of("warmup", "teleop").contains(phase));
    if (moving) {
      if (evidence.enabledLoops == 0) failures.add("no actual enabled loops");
      if (evidence.appliedDriveRequests == 0) failures.add("no applied drive requests");
      if (evidence.maxDesiredSpeed <= .01
          || (terminalVoltageModelled && evidence.maxDriveVoltage <= .01))
        failures.add("no actuator command evolution");
      if (evidence.maxMeasuredSpeed <= .01 || evidence.distanceMeters <= .001)
        failures.add("no measured motion");
      if (autonomous) {
        if (evidence.autonomousCommandExecutions == 0)
          failures.add("PathPlanner fixture did not execute");
      } else {
        if (evidence.nonzeroInputLoops == 0 || evidence.driverCommandExecutions == 0)
          failures.add("real driver command path did not execute nonzero inputs");
      }
    }
    return List.copyOf(failures);
  }
}
