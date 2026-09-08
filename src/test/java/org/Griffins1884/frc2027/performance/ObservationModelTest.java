package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ObservationModelTest {
  private static LoopSample sample(long cycle, long startUs, long endUs) {
    return new LoopSample(
        cycle,
        "teleop",
        startUs * 1000,
        endUs * 1000,
        0,
        startUs,
        startUs,
        endUs,
        20_000,
        20_000_000,
        startUs / 20_000);
  }

  @Test
  void slowExecutionMissesBothBudgets() {
    var sample = sample(0, 0, 25_000);
    assertTrue(sample.executionOverrun());
    assertTrue(sample.deadlineMiss());
    assertEquals(25, sample.executionMs());
    assertEquals(-5, sample.headroomMs());
  }

  @Test
  void lateShortExecutionStillMissesOriginalDeadline() {
    var sample = sample(0, 25_000, 26_000);
    assertFalse(sample.executionOverrun());
    assertTrue(sample.deadlineMiss());
    assertEquals(1, sample.executionMs());
    assertEquals(25, sample.latenessMs());
    assertEquals(1, sample.skippedReleases());
  }

  @Test
  void exactDeadlineIsNotAMissAndUnitsRemainDistinct() {
    var sample = sample(0, 1_000, 20_000);
    assertFalse(sample.deadlineMiss());
    assertEquals(19, sample.executionMs());
    assertEquals(0, sample.headroomMs());
  }

  @Test
  void boundedQueueReportsDroppedRecordsAndRecoversWithoutOverwrite() {
    var queue = new LoopSampleQueue(2);
    assertTrue(queue.offer(sample(1, 0, 10)));
    assertTrue(queue.offer(sample(2, 0, 10)));
    assertFalse(queue.offer(sample(3, 0, 10)));
    assertEquals(1, queue.dropped());
    assertEquals(1, queue.poll().cycle());
    assertTrue(queue.offer(sample(4, 0, 10)));
    assertEquals(2, queue.poll().cycle());
    assertEquals(4, queue.poll().cycle());
    assertNull(queue.poll());
    assertEquals(0, queue.size());
  }

  @Test
  void missingCpuMetricsRemainUnknown() {
    assertNull(ResourceProbe.available(-1));
    assertNull(
        ResourceProbe.readCpu(
            () -> {
              throw new UnsupportedOperationException();
            }));
    assertNull(
        ResourceProbe.readCpu(
            () -> {
              throw new SecurityException();
            }));
    assertEquals(0L, ResourceProbe.available(0));
    assertThrows(IllegalArgumentException.class, () -> new LoopSampleQueue(4097));
  }

  @Test
  void captureBudgetExpiresAtBoundaryWithoutDependingOnHalClock() {
    var window = new CaptureWindow(100, 600);
    assertFalse(window.expired(699));
    assertTrue(window.expired(700));
    assertTrue(window.expired(701));
    assertThrows(IllegalArgumentException.class, () -> new CaptureWindow(0, 0));
    var wrappingClock = new CaptureWindow(Long.MAX_VALUE - 10, 20);
    assertTrue(wrappingClock.expired(Long.MIN_VALUE + 10));
  }

  @Test
  void threadCategoriesAreFixedAndUnknownThreadsAreExcluded() {
    assertEquals("main_loop", ResourceProbe.threadGroup(5, "arbitrary-main-name", 5));
    assertEquals("http", ResourceProbe.threadGroup(6, "OperatorBoardHttp-2", 5));
    assertEquals("odometry", ResourceProbe.threadGroup(6, "PhoenixOdometryThread", 5));
    assertEquals("configuration", ResourceProbe.threadGroup(6, "SwerveConfiguration", 5));
    assertEquals("log_receiver", ResourceProbe.threadGroup(6, "AdvantageKit_LogReceiver", 5));
    assertNull(ResourceProbe.threadGroup(6, "unrelated-thread", 5));
  }

  @Test
  void lightweightProbeDoesNotCollectThreadHistory() {
    var snapshot = ResourceProbe.sample(false);
    assertTrue(snapshot.selectedThreads().isEmpty());
    assertFalse(snapshot.threadCpuAvailable());
    assertFalse(snapshot.selectedThreadsTruncated());
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.selectedThreads().add(new ResourceProbe.ThreadSample(1, "http", 1L)));
  }
}
