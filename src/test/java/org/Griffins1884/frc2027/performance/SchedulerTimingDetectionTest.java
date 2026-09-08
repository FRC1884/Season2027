package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Controlled execution clock: tests detection without requiring any desktop execution speed. */
class SchedulerTimingDetectionTest {
  @Test
  void realSchedulerInvokesSlowFakeSubsystemAndItsRecordedBudgetMissIsDetected() {
    assertTrue(HAL.initialize(500, 0));
    AtomicLong clock = new AtomicLong();
    Subsystem slow =
        new Subsystem() {
          @Override
          public void periodic() {
            clock.addAndGet(25_000_000);
          }
        };
    CommandScheduler scheduler = CommandScheduler.getInstance();
    scheduler.registerSubsystem(slow);
    try {
      long start = clock.get();
      scheduler.run();
      long end = clock.get();
      LoopSample sample =
          new LoopSample(
              0, "fake-clock-fixture", start, end, 0, 0, start / 1000, end / 1000, 20_000, -1, 0);
      assertEquals(25.0, sample.executionMs());
      assertTrue(sample.executionOverrun());
      assertTrue(sample.deadlineMiss());
    } finally {
      scheduler.unregisterSubsystem(slow);
    }
  }
}
