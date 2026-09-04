package org.Griffins1884.frc2027.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.Griffins1884.frc2027.GlobalConstants;

/** Runtime logging/tuning profile shared by the dashboard config UI and robot code. */
public record RuntimeModeProfile(
    GlobalConstants.LoggingMode loggingMode,
    boolean tuningEnabled,
    Set<String> debugSubsystems,
    Set<String> loggedSignals,
    Set<String> publishedSignals) {

  private static final Set<String> DEBUG_SIGNAL_SET =
      Set.of(
          "IDENTITY",
          "CONNECTION",
          "FAULTS",
          "TEMPERATURE",
          "VOLTAGE",
          "CURRENT",
          "POSITION",
          "VELOCITY",
          "APPLIED_OUTPUT",
          "TARGET",
          "CLOSED_LOOP",
          "LIMIT_SWITCH",
          "SENSOR_STATE",
          "HEALTH",
          "CONFIG_SNAPSHOT");

  private static final Set<String> COMP_SIGNAL_SET =
      Set.of("IDENTITY", "CONNECTION", "FAULTS", "HEALTH", "TARGET");

  public RuntimeModeProfile {
    loggingMode = loggingMode != null ? loggingMode : GlobalConstants.LoggingMode.COMP;
    debugSubsystems = normalizeSubsystems(debugSubsystems);
    loggedSignals = normalizeSignals(loggedSignals, loggingMode);
    publishedSignals = normalizeSignals(publishedSignals, loggingMode);
  }

  public static RuntimeModeProfile fromGlobals() {
    return new RuntimeModeProfile(
        GlobalConstants.LOGGING_MODE,
        GlobalConstants.TUNING_MODE,
        Collections.emptySet(),
        null,
        null);
  }

  public boolean isDebugMode() {
    return loggingMode == GlobalConstants.LoggingMode.DEBUG;
  }

  public boolean isDebugEnabled(String subsystemKey) {
    if (isDebugMode()) {
      return true;
    }
    if (subsystemKey == null || subsystemKey.isBlank()) {
      return false;
    }
    return debugSubsystems.contains(subsystemKey.trim().toLowerCase());
  }

  public boolean allowsTuning(boolean allowInCompMode) {
    return tuningEnabled || (allowInCompMode && loggingMode == GlobalConstants.LoggingMode.COMP);
  }

  public boolean shouldLog(String signal, String subsystemKey) {
    String normalizedSignal = normalizeSignal(signal);
    return loggedSignals.contains(normalizedSignal)
        || (isDebugEnabled(subsystemKey) && DEBUG_SIGNAL_SET.contains(normalizedSignal));
  }

  public boolean shouldPublish(String signal, String subsystemKey) {
    String normalizedSignal = normalizeSignal(signal);
    return publishedSignals.contains(normalizedSignal)
        || (isDebugEnabled(subsystemKey) && DEBUG_SIGNAL_SET.contains(normalizedSignal));
  }

  private static Set<String> normalizeSubsystems(Set<String> subsystems) {
    if (subsystems == null || subsystems.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> normalized = new HashSet<>();
    for (String subsystem : subsystems) {
      if (subsystem != null && !subsystem.isBlank()) {
        normalized.add(subsystem.trim().toLowerCase());
      }
    }
    return Collections.unmodifiableSet(normalized);
  }

  private static Set<String> normalizeSignals(
      Set<String> signals, GlobalConstants.LoggingMode loggingMode) {
    if (signals == null || signals.isEmpty()) {
      return loggingMode == GlobalConstants.LoggingMode.DEBUG ? DEBUG_SIGNAL_SET : COMP_SIGNAL_SET;
    }

    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String signal : signals) {
      String normalizedSignal = normalizeSignal(signal);
      if (!normalizedSignal.isEmpty()) {
        normalized.add(normalizedSignal);
      }
    }
    return Collections.unmodifiableSet(normalized);
  }

  private static String normalizeSignal(String signal) {
    return signal == null ? "" : signal.trim().toUpperCase();
  }
}
