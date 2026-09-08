package org.Griffins1884.frc2027.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TelemetryCadenceTest {
  @Test
  void competitionPublishesInitiallyThenEveryFiveTwentyMillisecondCycles() {
    var cadence = new TelemetryCadence();
    double clock = 10.0;
    int publications = 0;
    for (int cycle = 0; cycle < 100; cycle++) {
      boolean publish = cadence.shouldPublish(clock, false);
      assertEquals(cycle % 5 == 0, publish);
      if (publish) publications++;
      clock += 0.02;
    }
    assertEquals(20, publications);
  }

  @Test
  void fullRateAndBothModeTransitionsPublishImmediately() {
    var cadence = new TelemetryCadence();
    assertTrue(cadence.shouldPublish(1.0, false));
    assertFalse(cadence.shouldPublish(1.02, false));
    assertTrue(cadence.shouldPublish(1.04, true));
    assertTrue(cadence.shouldPublish(1.04, true));
    assertTrue(cadence.shouldPublish(1.06, true));
    assertTrue(cadence.shouldPublish(1.08, false));
    assertFalse(cadence.shouldPublish(1.1, false));
    assertTrue(cadence.shouldPublish(1.18, false));
  }

  @Test
  void backwardsClockRestartsCadenceEvenBetweenPublications() {
    var cadence = new TelemetryCadence();
    assertTrue(cadence.shouldPublish(2.0, false));
    assertFalse(cadence.shouldPublish(2.08, false));
    assertTrue(cadence.shouldPublish(2.04, false));
    assertFalse(cadence.shouldPublish(2.06, false));
    assertTrue(cadence.shouldPublish(2.14, false));
  }
}
