package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopScenarioTest {
  private DesktopScenario scenario(String profile, long seed) {
    return new DesktopScenario(
        profile,
        seed,
        "stepped",
        true,
        false,
        15810,
        false,
        List.of(
            new DesktopScenario.Phase("disabled_before_auto", 1),
            new DesktopScenario.Phase("auto_fixture", 2),
            new DesktopScenario.Phase("teleop", 3),
            new DesktopScenario.Phase("disabled_recovery", 1)));
  }

  @Test
  void phasesHaveExactBoundariesAndFiniteEnd() {
    var s = scenario("NORMAL", 1884);
    assertEquals("disabled_before_auto", s.at(0.999).name());
    assertEquals("auto_fixture", s.at(1).name());
    assertEquals("teleop", s.at(3).name());
    assertEquals("complete", s.at(7).name());
    assertFalse(s.enabled(s.at(0)));
    assertTrue(s.enabled(s.at(1)));
    assertFalse(s.enabled(s.at(6)));
  }

  @Test
  void seedIsRepeatableAndAllInputsRemainWithinHidLimits() {
    var a = scenario("HARD", 1884);
    var b = scenario("HARD", 1884);
    var c = scenario("HARD", 1885);
    assertNotEquals(a.inputs(1), c.inputs(1));
    for (int i = 0; i < 10000; i++) {
      var input = a.inputs(i * .02);
      assertEquals(input, b.inputs(i * .02));
      assertTrue(
          Math.abs(input.forward()) <= 1
              && Math.abs(input.strafe()) <= 1
              && Math.abs(input.rotate()) <= 1);
    }
  }

  @Test
  void idleNeverEnablesOrInjectsMovement() {
    for (String profile : List.of("IDLE", "IDLE_DASHBOARD")) {
      var s = scenario(profile, 1);
      assertFalse(s.enabled(s.at(1)));
      assertEquals(new DesktopScenario.Inputs(0, 0, 0, 0), s.inputs(3));
    }
  }

  @Test
  void rejectsUnboundedDurationUnknownProfileAndInvalidPort() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesktopScenario(
                "NORMAL",
                1,
                "paced",
                true,
                false,
                15810,
                false,
                List.of(new DesktopScenario.Phase("teleop", Double.NaN))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesktopScenario(
                "NORMAL",
                1,
                "paced",
                true,
                false,
                15810,
                false,
                List.of(new DesktopScenario.Phase("teleop", 3601))));
    assertThrows(IllegalArgumentException.class, () -> scenario("UNKNOWN", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesktopScenario(
                "NORMAL",
                1,
                "paced",
                true,
                false,
                0,
                false,
                List.of(new DesktopScenario.Phase("teleop", 1))));
  }
}
