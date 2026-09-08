package org.Griffins1884.frc2027.performance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Finite engineering timeline and seeded driver waveforms, independent of HAL. */
public record DesktopScenario(
    String profile,
    long seed,
    String timing,
    boolean recording,
    boolean detailed,
    int ntPort,
    boolean waitForStart,
    List<Phase> phases,
    String fault) {
  private static final Set<String> PROFILES =
      Set.of(
          "IDLE",
          "IDLE_DASHBOARD",
          "EASY",
          "NORMAL",
          "HARD",
          "HARD_DEBUG",
          "OVERLOAD_RECOVERY",
          "SOAK");

  public DesktopScenario(
      String profile,
      long seed,
      String timing,
      boolean recording,
      boolean detailed,
      int ntPort,
      boolean waitForStart,
      List<Phase> phases) {
    this(profile, seed, timing, recording, detailed, ntPort, waitForStart, phases, "none");
  }

  public DesktopScenario {
    if (!Set.of("none", "blocked_teleop", "empty_auto").contains(fault))
      throw new IllegalArgumentException("Unknown desktop fault: " + fault);
    if (!PROFILES.contains(profile))
      throw new IllegalArgumentException("Unknown profile: " + profile);
    if (!Set.of("paced", "stepped").contains(timing))
      throw new IllegalArgumentException("timing must be paced or stepped");
    if (ntPort < 1024 || ntPort > 65535)
      throw new IllegalArgumentException("ntPort must be explicit loopback port 1024..65535");
    phases = List.copyOf(phases);
    if (phases.isEmpty() || phases.size() > 256)
      throw new IllegalArgumentException("phases must contain 1..256 entries");
    double total = 0;
    for (Phase phase : phases) {
      if (!Set.of(
              "warmup",
              "disabled",
              "autonomous",
              "transition",
              "teleop",
              "recovery",
              "disabled_before_auto",
              "auto_fixture",
              "disabled_transition",
              "disabled_recovery")
          .contains(phase.name()))
        throw new IllegalArgumentException("Unknown phase: " + phase.name());
      if (!Double.isFinite(phase.durationSeconds()) || phase.durationSeconds() <= 0)
        throw new IllegalArgumentException("Phase duration must be finite and positive");
      total += phase.durationSeconds();
    }
    if (total > 3600)
      throw new IllegalArgumentException("Total desktop duration exceeds 3600 seconds");
  }

  public static DesktopScenario fromJson(JsonNode value) {
    List<Phase> phases = new ArrayList<>();
    for (JsonNode phase : value.path("phases"))
      phases.add(
          new Phase(
              phase.path("name").asText(), phase.path("durationSeconds").asDouble(Double.NaN)));
    return new DesktopScenario(
        value.path("profile").asText("NORMAL"),
        value.path("seed").asLong(1884),
        value.path("timing").asText("paced"),
        value.path("recording").asBoolean(true),
        value.path("detailed").asBoolean(false),
        value.path("ntPort").asInt(0),
        value.path("waitForStart").asBoolean(false),
        phases,
        value.path("fault").asText("none"));
  }

  public double durationSeconds() {
    return phases.stream().mapToDouble(Phase::durationSeconds).sum();
  }

  public PhasePosition at(double seconds) {
    double start = 0;
    for (int i = 0; i < phases.size(); i++) {
      Phase phase = phases.get(i);
      if (seconds < start + phase.durationSeconds())
        return new PhasePosition(i, phase.name(), seconds - start);
      start += phase.durationSeconds();
    }
    return new PhasePosition(phases.size(), "complete", 0);
  }

  public boolean enabled(PhasePosition phase) {
    return !profile.startsWith("IDLE")
        && Set.of("warmup", "autonomous", "auto_fixture", "teleop").contains(phase.name());
  }

  public Inputs inputs(double seconds) {
    if (profile.startsWith("IDLE")) return new Inputs(0, 0, 0, 0);
    double offset = Math.floorMod(seed, 10000) / 10000.0 * Math.PI * 2;
    double amplitude = profile.equals("EASY") ? 0.3 : profile.equals("NORMAL") ? 0.65 : 0.95;
    double frequency = profile.equals("EASY") ? 0.2 : profile.equals("NORMAL") ? 0.8 : 2.3;
    double forward = amplitude * Math.sin(seconds * frequency + offset);
    double strafe =
        profile.equals("EASY")
            ? 0
            : amplitude * 0.8 * Math.sin(seconds * frequency * 0.73 + offset / 2);
    double rotate = amplitude * 0.65 * Math.sin(seconds * frequency * 0.57);
    double override = profile.equals("EASY") ? 0 : ((int) (seconds / 7) % 2 == 0 ? 0 : 0.8);
    return new Inputs(forward, strafe, rotate, override);
  }

  public record Phase(String name, double durationSeconds) {}

  public record PhasePosition(int index, String name, double elapsedSeconds) {}

  public record Inputs(double forward, double strafe, double rotate, double robotRelative) {}
}
