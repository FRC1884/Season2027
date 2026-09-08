package org.Griffins1884.frc2027.subsystems.swerve;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.CANBus;
import com.ctre.phoenix6.StatusSignal;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.Threads;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import org.Griffins1884.frc2027.GlobalConstants;

public class PhoenixOdometryThread extends Thread {
  private volatile long droppedSamples;
  private volatile long generation;
  private static final int QUEUE_CAPACITY = 20;

  public long getDroppedSamples() {
    return droppedSamples;
  }

  /** Caller holds odometryLock; reject any refresh begun before a sensor reset. */
  void invalidatePendingSamples() {
    generation++;
  }

  public void shutdown() {
    interrupt();
    boolean interrupted = false;
    while (isAlive()) {
      try {
        join();
      } catch (InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private final Lock signalsLock = new ReentrantLock();
  private BaseStatusSignal[] phoenixSignals = new BaseStatusSignal[0];
  private final List<DoubleSupplier> genericSignals = new ArrayList<>();
  private final List<Queue<Double>> phoenixQueues = new ArrayList<>();
  private final List<Queue<Double>> genericQueues = new ArrayList<>();
  private final List<Queue<Double>> timestampQueues = new ArrayList<>();

  private static boolean isCanFd = detectCanFd();
  private static PhoenixOdometryThread instance;

  private static boolean detectCanFd() {
    if (!RobotBase.isReal()) {
      return false;
    }
    try {
      return new CANBus("").isNetworkFD();
    } catch (LinkageError | RuntimeException exception) {
      return false;
    }
  }

  public static PhoenixOdometryThread getInstance() {
    if (instance == null) {
      instance = new PhoenixOdometryThread();
    }
    return instance;
  }

  PhoenixOdometryThread() {
    setName("PhoenixOdometryThread");
    setDaemon(true);
  }

  @Override
  public void start() {
    if (!timestampQueues.isEmpty()) {
      super.start();
    }
  }

  public Queue<Double> registerSignal(StatusSignal<Angle> signal) {
    Queue<Double> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    signalsLock.lock();
    SwerveSubsystem.odometryLock.lock();
    try {
      BaseStatusSignal[] newSignals = new BaseStatusSignal[phoenixSignals.length + 1];
      System.arraycopy(phoenixSignals, 0, newSignals, 0, phoenixSignals.length);
      newSignals[phoenixSignals.length] = signal;
      phoenixSignals = newSignals;
      phoenixQueues.add(queue);
    } finally {
      signalsLock.unlock();
      SwerveSubsystem.odometryLock.unlock();
    }
    return queue;
  }

  public Queue<Double> registerSignal(DoubleSupplier signal) {
    Queue<Double> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    signalsLock.lock();
    SwerveSubsystem.odometryLock.lock();
    try {
      genericSignals.add(signal);
      genericQueues.add(queue);
    } finally {
      signalsLock.unlock();
      SwerveSubsystem.odometryLock.unlock();
    }
    return queue;
  }

  public Queue<Double> makeTimestampQueue() {
    Queue<Double> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    SwerveSubsystem.odometryLock.lock();
    try {
      timestampQueues.add(queue);
    } finally {
      SwerveSubsystem.odometryLock.unlock();
    }
    return queue;
  }

  @Override
  public void run() {
    Threads.setCurrentThreadPriority(true, 1);

    while (!isInterrupted()) {
      long sampleGeneration = generation;
      signalsLock.lock();
      try {
        if (isCanFd && phoenixSignals.length > 0) {
          BaseStatusSignal.waitForAll(2.0 / GlobalConstants.ODOMETRY_FREQUENCY, phoenixSignals);
        } else {
          Thread.sleep((long) (1000.0 / GlobalConstants.ODOMETRY_FREQUENCY));
          if (phoenixSignals.length > 0) {
            BaseStatusSignal.refreshAll(phoenixSignals);
          }
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } finally {
        signalsLock.unlock();
      }

      SwerveSubsystem.odometryLock.lock();
      try {
        if (sampleGeneration != generation) {
          droppedSamples++;
          continue;
        }
        double timestamp = RobotController.getFPGATime() / 1e6;
        double totalLatency = 0.0;
        for (BaseStatusSignal signal : phoenixSignals) {
          totalLatency += signal.getTimestamp().getLatency();
        }
        if (phoenixSignals.length > 0) {
          timestamp -= totalLatency / phoenixSignals.length;
        }

        publishSample(timestamp);

      } finally {
        SwerveSubsystem.odometryLock.unlock();
      }
    }
  }

  private static boolean anyQueueFull(List<Queue<Double>> queues) {
    for (Queue<Double> queue : queues) if (queue.size() >= QUEUE_CAPACITY) return true;
    return false;
  }

  /** All channels append together or all drop together; caller holds odometryLock. */
  void publishSample(double timestamp) {
    if (anyQueueFull(phoenixQueues)
        || anyQueueFull(genericQueues)
        || anyQueueFull(timestampQueues)) {
      droppedSamples++;
      return;
    }
    for (int i = 0; i < phoenixSignals.length; i++)
      phoenixQueues.get(i).offer(phoenixSignals[i].getValueAsDouble());
    for (int i = 0; i < genericSignals.size(); i++)
      genericQueues.get(i).offer(genericSignals.get(i).getAsDouble());
    for (Queue<Double> queue : timestampQueues) queue.offer(timestamp);
  }
}
