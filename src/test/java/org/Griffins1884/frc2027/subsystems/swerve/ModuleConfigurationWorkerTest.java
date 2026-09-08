package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModuleConfigurationWorkerTest {
  private static final ModuleConfiguration INITIAL = new ModuleConfiguration(1, 2, 3, 4, 5, 6);
  private static final ModuleConfiguration UPDATED = new ModuleConfiguration(7, 8, 9, 10, 11, 12);

  @Test
  void initialConfigurationAndDuplicatesApplyOnlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(() -> true)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                assertEquals(INITIAL, gains);
                assertTrue(brake);
                calls.incrementAndGet();
                return "";
              });
      handle.initialize(INITIAL);
      handle.request(INITIAL);
      handle.setBrakeMode(true);
      assertTrue(handle.isReady());
      assertEquals(1, calls.get());
      assertEquals(1, handle.status().appliedRevision());
    }
  }

  @Test
  void stalledApplyCoalescesAndCannotApproveNewerRevisionOrEnable() throws Exception {
    AtomicBoolean disabled = new AtomicBoolean(true);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(disabled::get)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                if (calls.incrementAndGet() == 2) {
                  entered.countDown();
                  release.await();
                }
                return "";
              });
      handle.initialize(INITIAL);
      handle.request(UPDATED);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // These calls finish while the backend remains stalled, with no timing threshold.
      handle.request(INITIAL);
      handle.request(INITIAL);
      handle.setBrakeMode(false);
      assertEquals(4, handle.status().desiredRevision());
      disabled.set(false);
      handle.updateState(false);
      assertFalse(handle.isReady());
      release.countDown();
      worker.awaitIdleForTest();
      assertEquals(2, handle.status().appliedRevision());
      assertFalse(handle.isReady());
      disabled.set(true);
      handle.updateState(true);
      worker.awaitIdleForTest();
      handle.updateState(true);
      assertTrue(handle.isReady());
      assertEquals(3, calls.get());
      assertEquals(4, handle.status().appliedRevision());
    } finally {
      release.countDown();
    }
  }

  @Test
  void failuresAreBoundedAndRemainUnreadyUntilNewSuccessfulRequest() {
    AtomicBoolean failure = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(() -> true)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                calls.incrementAndGet();
                return failure.get() ? "failure" : "";
              });
      handle.initialize(INITIAL);
      assertEquals(5, calls.get());
      assertTrue(handle.status().failed());
      assertFalse(handle.isReady());
      failure.set(false);
      handle.request(UPDATED);
      worker.awaitIdleForTest();
      handle.updateState(true);
      assertTrue(handle.isReady());
      assertEquals(6, calls.get());
      assertEquals(5, handle.status().failureCount());
      assertEquals(1, handle.status().lastFailedRevision());
      assertEquals("failure", handle.status().lastFailureError());
    }
  }

  @Test
  void enableBetweenAttemptsStopsRetriesAndRequiresDisabledAcknowledgement() {
    AtomicBoolean disabled = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(disabled::get)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                if (calls.incrementAndGet() == 2) {
                  disabled.set(false);
                  return "interrupted configuration";
                }
                return "";
              });
      handle.initialize(INITIAL);
      handle.request(UPDATED);
      worker.awaitIdleForTest();
      assertEquals(2, calls.get());
      assertTrue(handle.status().inhibited());
      assertFalse(handle.isReady());
      disabled.set(true);
      handle.updateState(true);
      worker.awaitIdleForTest();
      assertFalse(
          handle.isReady()); // disabled completion still needs the main-loop acknowledgement
      handle.updateState(true);
      assertTrue(handle.isReady());
      assertEquals(3, calls.get());
    }
  }

  @Test
  void brakeAndLatestGainsShareAnImmutableTransactionAndCapacityIsBounded() throws Exception {
    AtomicBoolean disabled = new AtomicBoolean(true);
    var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
    try (var worker = new ModuleConfigurationWorker(disabled::get)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                seen.add(gains.driveP() + ":" + brake);
                return "";
              });
      handle.initialize(INITIAL);
      disabled.set(false);
      handle.setBrakeMode(false);
      assertFalse(handle.request(UPDATED)); // tuning while enabled is rejected
      assertEquals(2, handle.status().desiredRevision());
      assertEquals(1, seen.size());
      disabled.set(true);
      handle.request(UPDATED);
      worker.awaitIdleForTest();
      handle.updateState(true);
      assertEquals("7.0:false", seen.get(seen.size() - 1));
      assertTrue(handle.isReady());
      worker.register((gains, brake) -> "");
      worker.register((gains, brake) -> "");
      worker.register((gains, brake) -> "");
      assertThrows(IllegalStateException.class, () -> worker.register((gains, brake) -> ""));
    }
  }

  @Test
  void closingInterruptsAStalledBackendAndJoinsItsThread() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    var worker = new ModuleConfigurationWorker(() -> true);
    var handle =
        worker.register(
            (gains, brake) -> {
              if (calls.incrementAndGet() == 2) {
                entered.countDown();
                new CountDownLatch(1).await();
              }
              return "";
            });
    try {
      handle.initialize(INITIAL);
      handle.request(UPDATED);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
    } finally {
      worker.close();
    }
    assertFalse(worker.isAliveForTest());
    assertFalse(handle.isReady());
  }

  @Test
  void repeatedEnableTransitionsCannotResetTheRevisionRetryBudget() {
    AtomicBoolean disabled = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(disabled::get)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                if (calls.incrementAndGet() == 1) return "";
                disabled.set(false);
                return "enabled during transaction";
              });
      handle.initialize(INITIAL);
      handle.request(UPDATED);
      for (int attempt = 0; attempt < 5; attempt++) {
        worker.awaitIdleForTest();
        assertEquals(attempt + 2, calls.get());
        disabled.set(true);
        handle.updateState(true);
      }
      worker.awaitIdleForTest();
      assertEquals(6, calls.get());
      assertTrue(handle.status().failed());
      assertFalse(handle.isReady());
    }
  }

  @Test
  void failedStaleRevisionRemainsObservableAfterNewerSuccess() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try (var worker = new ModuleConfigurationWorker(() -> true)) {
      var handle =
          worker.register(
              (gains, brake) -> {
                if (calls.incrementAndGet() == 2) {
                  entered.countDown();
                  release.await();
                }
                return gains.equals(UPDATED) ? "old revision failed" : "";
              });
      handle.initialize(INITIAL);
      handle.request(UPDATED);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      handle.request(INITIAL);
      release.countDown();
      worker.awaitIdleForTest();
      handle.updateState(true);
      assertTrue(handle.isReady());
      assertEquals(3, handle.status().appliedRevision());
      assertEquals(5, handle.status().failureCount());
      assertEquals(2, handle.status().lastFailedRevision());
      assertEquals("old revision failed", handle.status().lastFailureError());
    } finally {
      release.countDown();
    }
  }

  @Test
  void closeJoinsAndRejectsWork() {
    var worker = new ModuleConfigurationWorker(() -> true);
    var handle = worker.register((gains, brake) -> "");
    handle.initialize(INITIAL);
    worker.close();
    assertFalse(worker.isAliveForTest());
    assertFalse(handle.isReady());
    handle.request(UPDATED);
    assertEquals(1, handle.status().desiredRevision());
  }
}
