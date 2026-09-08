package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import java.util.Queue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PhoenixOdometryQueueTest {
  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @Test
  void overflowDropsTheEntireSampleEvenIfOnlyOneQueueIsFull() {
    PhoenixOdometryThread producer = new PhoenixOdometryThread();
    Queue<Double> wheel = producer.registerSignal(() -> 2.0);
    Queue<Double> yaw = producer.registerSignal(() -> 3.0);
    Queue<Double> timestamps = producer.makeTimestampQueue();
    SwerveSubsystem.odometryLock.lock();
    try {
      for (int i = 0; i < 20; i++) producer.publishSample(i * 0.004);
      assertEquals(20, wheel.size());
      assertEquals(20, yaw.size());
      assertEquals(20, timestamps.size());
      producer.publishSample(0.080);
      assertEquals(1, producer.getDroppedSamples());
      wheel.poll(); // Deliberately asymmetric test state; remaining channels must not drift.
      producer.publishSample(0.084);
      assertEquals(19, wheel.size());
      assertEquals(20, yaw.size());
      assertEquals(20, timestamps.size());
      assertEquals(2, producer.getDroppedSamples());
      wheel.clear();
      yaw.clear();
      timestamps.clear();
      producer.publishSample(0.088);
      assertEquals(2.0, wheel.remove());
      assertEquals(3.0, yaw.remove());
      assertEquals(0.088, timestamps.remove());
    } finally {
      SwerveSubsystem.odometryLock.unlock();
      producer.shutdown();
    }
  }
}
