package org.Griffins1884.frc2027.performance;

import edu.wpi.first.hal.DriverStationJNI;
import edu.wpi.first.hal.NotifierJNI;
import edu.wpi.first.wpilibj.RobotController;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.Supplier;
import org.Griffins1884.frc2027.Robot;
import org.Griffins1884.frc2027.RobotContainer;
import org.littletonrobotics.junction.AutoLogOutputManager;
import org.littletonrobotics.junction.Logger;

/**
 * Startup opt-in observer. Scheduling and logger ordering follow LoggedRobot 26.0.2. The normal
 * Robot entry point does not instantiate this class. No Driver Station injection is provided here.
 *
 * <p>Elapsed execution includes logger before/after, loopFunc (including simulation), and GC
 * probes. The original deadline is retained before the pinned scheduler rebases an overdue release.
 * Desktop fixture callbacks are outside this measured interval. Accelerated HAL time is never
 * CPU/wall time.
 */
public class ObservedRobot extends Robot {
  private final int observerNotifier = NotifierJNI.initializeNotifier();
  private final boolean recordingEnabled;
  private final boolean detailed;
  private final long captureNanos;
  private final List<GarbageCollectorMXBean> gcBeans =
      ManagementFactory.getGarbageCollectorMXBeans();
  private final long[] lastGcTimes = new long[gcBeans.size()];
  private final long[] lastGcCounts = new long[gcBeans.size()];
  private volatile boolean stopping;
  private boolean closed;
  private LoopSampleQueue sampleQueue;
  private LoopSample pending;
  private long loggerQueueFaultCycles;
  private long completedSamples;
  private boolean captureComplete;
  private volatile ResourceProbe.Snapshot resources;
  private long simulationNanos = -1;

  /** Validate startup arguments before constructing the robot or its hardware IO. */
  public static ObservedRobot fromProperties() {
    double seconds = Double.parseDouble(System.getProperty("frc.performance.seconds", "300"));
    if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 600) {
      throw new IllegalArgumentException("frc.performance.seconds must be in (0, 600]");
    }
    return new ObservedRobot(true, Boolean.getBoolean("frc.performance.detailed"), seconds);
  }

  public ObservedRobot() {
    this(
        true,
        Boolean.getBoolean("frc.performance.detailed"),
        Double.parseDouble(System.getProperty("frc.performance.seconds", "300")));
  }

  public ObservedRobot(boolean recordingEnabled, boolean detailed, double captureSeconds) {
    this(recordingEnabled, detailed, validatedCaptureNanos(captureSeconds), RobotContainer::new);
  }

  /** Test-only composition seam; the desktop caller must reject real HAL before construction. */
  protected ObservedRobot(
      boolean recordingEnabled,
      boolean detailed,
      double captureSeconds,
      Supplier<RobotContainer> containerFactory) {
    this(recordingEnabled, detailed, validatedCaptureNanos(captureSeconds), containerFactory);
  }

  private static long validatedCaptureNanos(double captureSeconds) {
    if (System.getProperty("frc.mode", "").trim().equalsIgnoreCase("replay")) {
      throw new IllegalArgumentException("ObservedRobot does not implement full-match replay");
    }
    if (!Double.isFinite(captureSeconds)
        || captureSeconds <= 0
        || captureSeconds > (isReal() ? 600 : 7200)) {
      throw new IllegalArgumentException(
          "Capture seconds must be positive and <=600 real /7200 desktop");
    }
    return (long) (captureSeconds * 1e9);
  }

  private ObservedRobot(
      boolean recordingEnabled,
      boolean detailed,
      long captureNanos,
      Supplier<RobotContainer> containerFactory) {
    super(containerFactory);
    this.recordingEnabled = recordingEnabled;
    this.detailed = detailed;
    this.captureNanos = captureNanos;
    // Our subclass package is narrower than Robot's; preserve recursive production annotations.
    AutoLogOutputManager.addPackage(Robot.class.getPackageName());
    NotifierJNI.setNotifierName(observerNotifier, "ObservedRobot");
  }

  /** Only the isolated desktop stepped runner overrides this; normal scheduling remains paced. */
  protected boolean useNotifierTiming() {
    return true;
  }

  /** Called before the profiled interval; desktop subclasses alone may stage simulated inputs. */
  protected void beforeObservedLoop(long cycle) {}

  /** Called after the profiled interval, including when detailed recording is disabled. */
  protected void afterObservedLoop(LoopSample sample) {}

  protected String observationPhase() {
    return "HUMAN_OPERATED";
  }

  /**
   * Optional bounded single-consumer desktop collection; hardware uses the existing log receiver.
   */
  public final void enableSampleQueue() {
    if (sampleQueue == null) {
      sampleQueue = new LoopSampleQueue(4096);
    }
  }

  public final LoopSample pollSample() {
    return sampleQueue == null ? null : sampleQueue.poll();
  }

  public final long droppedSamples() {
    return sampleQueue == null ? 0 : sampleQueue.dropped();
  }

  public final long loggerQueueFaultCycles() {
    return loggerQueueFaultCycles;
  }

  public final ResourceProbe.Snapshot latestResources() {
    return resources;
  }

  public final boolean isCaptureComplete() {
    return captureComplete;
  }

  @Override
  public void startCompetition() {
    long periodUs = Math.round(getPeriod() * 1e6);
    try {
      if (getRobotContainer().getDrive() != null) {
        getRobotContainer()
            .getDrive()
            .setPerformanceObservationEnabled(recordingEnabled || isSimulation());
      }
      robotInit();
      if (isSimulation()) {
        simulationInit();
      }
      AutoLogOutputManager.addObject(this);
      Logger.recordOutput("MatchPerformance/SchemaVersion", 1);
      Logger.recordOutput(
          "MatchPerformance/RunId",
          System.getProperty("frc.performance.runId", java.util.UUID.randomUUID().toString()));
      Logger.recordOutput(
          "MatchPerformance/Platform", isReal() ? "ROBORIO_OBSERVE" : "DESKTOP_MATCH_SIM");
      Logger.recordOutput("MatchPerformance/PeriodUs", periodUs);
      Logger.recordOutput("MatchPerformance/HALRuntimeType", getRuntimeType().name());
      // Read-only pinned WPILib HAL identity. Simulation values are labelled, not real firmware.
      Logger.recordOutput("MatchPerformance/HardwareIdentityAvailable", isReal());
      if (isReal()) {
        Logger.recordOutput("MatchPerformance/FPGAVersion", RobotController.getFPGAVersion());
        Logger.recordOutput("MatchPerformance/FPGARevision", RobotController.getFPGARevision());
        Logger.recordOutput(
            "MatchPerformance/RobotSerialNumber", RobotController.getSerialNumber());
      }
      Logger.recordOutput("MatchPerformance/StartedUTC", java.time.Instant.now().toString());
      Logger.recordOutput(
          "MatchPerformance/JavaVersion",
          System.getProperty("java.runtime.version", "UNAVAILABLE"));
      Logger.recordOutput("MatchPerformance/VM", System.getProperty("java.vm.name", "UNAVAILABLE"));
      Logger.recordOutput("MatchPerformance/OS", System.getProperty("os.name", "UNAVAILABLE"));
      Logger.recordOutput(
          "MatchPerformance/OSVersion", System.getProperty("os.version", "UNAVAILABLE"));
      Logger.recordOutput(
          "MatchPerformance/Architecture", System.getProperty("os.arch", "UNAVAILABLE"));
      Logger.recordOutput(
          "MatchPerformance/ArtifactSha256",
          System.getProperty("frc.performance.artifactSha256", "UNAVAILABLE"));
      String[] threadGroups = {"main_loop", "http", "configuration", "odometry", "log_receiver"};
      String[] threadSources = new String[threadGroups.length];
      for (int i = 0; i < threadGroups.length; i++) {
        threadSources[i] = ResourceProbe.sourceForThreadGroup(threadGroups[i]);
      }
      Logger.recordOutput("MatchPerformance/ThreadGroups", threadGroups);
      Logger.recordOutput("MatchPerformance/ThreadSources", threadSources);
      Logger.recordOutput("MatchPerformance/CaptureComplete", false);
      Logger.AdvancedHooks.invokePeriodicAfterUser(RobotController.getFPGATime(), 0);
      System.out.println("********** Robot program startup complete **********");
      DriverStationJNI.observeUserProgramStarting();
      long nextDueUs = RobotController.getFPGATime();
      long captureStart = System.nanoTime();
      CaptureWindow captureWindow = new CaptureWindow(captureStart, captureNanos);
      long priorStart = 0;
      long nextResource = captureStart;
      long cycle = 0;
      while (!stopping) {
        long originalDueUs = nextDueUs;
        long nowUs = RobotController.getFPGATime();
        if (useNotifierTiming()) {
          if (nextDueUs < nowUs) {
            nextDueUs = nowUs;
          } else {
            NotifierJNI.updateNotifierAlarm(observerNotifier, nextDueUs);
            if (NotifierJNI.waitForNotifierAlarm(observerNotifier) == 0L) {
              break;
            }
          }
        }
        long effectiveDueUs = nextDueUs;
        nextDueUs += periodUs;
        beforeObservedLoop(cycle);
        if (stopping) {
          break;
        }
        long startNanos = System.nanoTime();
        long startUs = RobotController.getFPGATime();
        Logger.AdvancedHooks.invokePeriodicBeforeUser();
        publishPending();
        if (recordingEnabled && !captureComplete && captureWindow.expired(startNanos)) {
          captureComplete = true;
          if (getRobotContainer().getDrive() != null) {
            getRobotContainer().getDrive().setPerformanceObservationEnabled(false);
          }
          publishCompletion();
        }
        if (recordingEnabled && !captureComplete && startNanos >= nextResource) {
          resources = ResourceProbe.sample(detailed);
          publishResources(resources);
          nextResource = startNanos + 1_000_000_000L;
        }
        long userStart = RobotController.getFPGATime();
        loopFunc();
        long userEnd = RobotController.getFPGATime();
        if (recordingEnabled && !captureComplete) {
          publishControlEvidence(cycle);
        }
        updateGcStats();
        Logger.AdvancedHooks.invokePeriodicAfterUser(userEnd - userStart, userStart - startUs);
        long endUs = RobotController.getFPGATime();
        long endNanos = System.nanoTime();
        if (Logger.getReceiverQueueFault()) {
          loggerQueueFaultCycles++;
        }
        LoopSample sample =
            new LoopSample(
                cycle,
                observationPhase(),
                startNanos,
                endNanos,
                originalDueUs,
                effectiveDueUs,
                startUs,
                endUs,
                periodUs,
                priorStart == 0 ? -1 : startNanos - priorStart,
                Math.max(0, (effectiveDueUs - originalDueUs) / periodUs));
        if (recordingEnabled && !captureComplete) {
          pending = sample;
          completedSamples++;
          if (sampleQueue != null) {
            sampleQueue.offer(sample);
          }
        }
        afterObservedLoop(sample);
        priorStart = startNanos;
        cycle++;
      }
      // Flush the final complete sample without another control/simulation cycle.
      Logger.AdvancedHooks.invokePeriodicBeforeUser();
      publishPending();
      captureComplete = true;
      publishCompletion();
      Logger.AdvancedHooks.invokePeriodicAfterUser(0, 0);
    } catch (Exception failure) {
      StringWriter trace = new StringWriter();
      failure.printStackTrace(new PrintWriter(trace));
      Logger.AdvancedHooks.invokePeriodicBeforeUser();
      publishPending();
      Logger.recordOutput("MatchPerformance/Aborted", true);
      Logger.AdvancedHooks.invokePeriodicAfterUser(0, 0, trace.toString());
      throw failure;
    } finally {
      Logger.end();
    }
  }

  private void publishPending() {
    if (pending == null) {
      return;
    }
    // These fields refer to PreviousCycle, not the containing LogTable's current timestamp.
    Logger.recordOutput("MatchPerformance/PreviousCycle", pending.cycle());
    Logger.recordOutput("MatchPerformance/PreviousPhase", pending.phase());
    Logger.recordOutput("MatchPerformance/StartNanos", pending.startNanos());
    Logger.recordOutput("MatchPerformance/EndNanos", pending.endNanos());
    Logger.recordOutput("MatchPerformance/OriginalDueUs", pending.originalDueUs());
    Logger.recordOutput("MatchPerformance/EffectiveDueUs", pending.effectiveDueUs());
    Logger.recordOutput("MatchPerformance/StartUs", pending.startUs());
    Logger.recordOutput("MatchPerformance/EndUs", pending.endUs());
    Logger.recordOutput("MatchPerformance/IntervalNanos", pending.intervalNanos());
    Logger.recordOutput("MatchPerformance/SkippedReleases", pending.skippedReleases());
    Logger.recordOutput("MatchPerformance/ExecutionMS", pending.executionMs());
    Logger.recordOutput("MatchPerformance/DeadlineMiss", pending.deadlineMiss());
    Logger.recordOutput("MatchPerformance/HeadroomMS", pending.headroomMs());
    Logger.recordOutput("MatchPerformance/DroppedSamples", droppedSamples());
    Logger.recordOutput("MatchPerformance/LoggerQueueFaultCycles", loggerQueueFaultCycles);
    pending = null;
  }

  private void publishCompletion() {
    Logger.recordOutput("MatchPerformance/CaptureComplete", true);
    Logger.recordOutput("MatchPerformance/CompletedSamples", completedSamples);
    Logger.recordOutput("MatchPerformance/DroppedSamples", droppedSamples());
    Logger.recordOutput("MatchPerformance/LoggerQueueFaultCycles", loggerQueueFaultCycles);
  }

  @Override
  public void simulationPeriodic() {
    if (recordingEnabled && !captureComplete) {
      long start = System.nanoTime();
      super.simulationPeriodic();
      simulationNanos = System.nanoTime() - start;
    } else {
      super.simulationPeriodic();
    }
  }

  /**
   * Physics cost is inclusive in loopFunc and reported separately, never subtracted as RIO time.
   */
  public final long simulationNanos() {
    return simulationNanos;
  }

  private void publishControlEvidence(long cycle) {
    Logger.recordOutput("MatchPerformance/CurrentCycle", cycle);
    Logger.recordOutput("MatchPerformance/CurrentPhase", observationPhase());
    Logger.recordOutput("MatchPerformance/SimulationNanos", simulationNanos);
    var drive = getRobotContainer().getDrive();
    if (drive != null) {
      var status = drive.getPerformanceSnapshot();
      Logger.recordOutput("MatchPerformance/Drive/Ready", status.ready());
      Logger.recordOutput("MatchPerformance/Drive/GyroConnected", status.gyroConnected());
      Logger.recordOutput("MatchPerformance/Drive/AcquiredSamples", status.acquiredSamples());
      Logger.recordOutput("MatchPerformance/Drive/ConsumedSamples", status.consumedSamples());
      Logger.recordOutput("MatchPerformance/Drive/InvalidSnapshots", status.invalidSnapshots());
      Logger.recordOutput("MatchPerformance/Drive/DroppedSamples", status.droppedProducerSamples());
      Logger.recordOutput("MatchPerformance/Drive/Requests", status.driveRequests());
      Logger.recordOutput("MatchPerformance/Drive/AppliedRequests", status.appliedDriveRequests());
      Logger.recordOutput(
          "MatchPerformance/Drive/LastSampleTimestampSeconds", status.lastSampleTimestampSeconds());
      Logger.recordOutput("MatchPerformance/Drive/SetpointNanos", status.setpointNanos());
      Logger.recordOutput("MatchPerformance/Drive/ControlNanos", status.controlNanos());
    }
  }

  private void publishResources(ResourceProbe.Snapshot sample) {
    Logger.recordOutput("MatchPerformance/Resources/MonotonicNanos", sample.monotonicNanos());
    Logger.recordOutput(
        "MatchPerformance/Resources/ProcessCpuAvailable", sample.processCpuNanos() != null);
    if (sample.processCpuNanos() != null) {
      Logger.recordOutput("MatchPerformance/Resources/ProcessCpuNanos", sample.processCpuNanos());
    }
    Logger.recordOutput("MatchPerformance/Resources/HeapUsedBytes", sample.heapUsedBytes());
    Logger.recordOutput(
        "MatchPerformance/Resources/HeapCommittedBytes", sample.heapCommittedBytes());
    Logger.recordOutput("MatchPerformance/Resources/HeapMaxBytes", sample.heapMaxBytes());
    Logger.recordOutput("MatchPerformance/Resources/Threads", sample.threads());
    Logger.recordOutput(
        "MatchPerformance/Resources/AvailableProcessors", sample.availableProcessors());
    Logger.recordOutput(
        "MatchPerformance/Resources/GCCountAvailable", sample.gcCollections() != null);
    Logger.recordOutput(
        "MatchPerformance/Resources/GCTimeAvailable", sample.gcTimeMillis() != null);
    if (sample.gcCollections() != null) {
      Logger.recordOutput("MatchPerformance/Resources/GCCollections", sample.gcCollections());
    }
    if (sample.gcTimeMillis() != null) {
      Logger.recordOutput("MatchPerformance/Resources/GCTimeMS", sample.gcTimeMillis());
    }
    var server = getRobotContainer().getOperatorBoardServer();
    if (server != null) {
      var http = server.getMetricsSnapshot();
      Logger.recordOutput("MatchPerformance/HTTP/Requests", http.requests());
      Logger.recordOutput("MatchPerformance/HTTP/BodyBytes", http.bodyBytes());
      Logger.recordOutput("MatchPerformance/HTTP/RejectedTasks", http.rejectedTasks());
      Logger.recordOutput("MatchPerformance/HTTP/ActiveWorkers", http.activeWorkers());
      Logger.recordOutput("MatchPerformance/HTTP/QueuedTasks", http.queuedTasks());
      Logger.recordOutput("MatchPerformance/HTTP/WorkerHighWater", http.workerHighWater());
      Logger.recordOutput("MatchPerformance/HTTP/QueueHighWater", http.queueHighWater());
    }
    if (detailed) {
      Logger.recordOutput(
          "MatchPerformance/Resources/ThreadCpuAvailable", sample.threadCpuAvailable());
      Logger.recordOutput(
          "MatchPerformance/Resources/SelectedThreadsTruncated", sample.selectedThreadsTruncated());
      int count = sample.selectedThreads().size();
      long[] ids = new long[count];
      long[] cpuNanos = new long[count];
      boolean[] available = new boolean[count];
      String[] groups = new String[count];
      for (int i = 0; i < count; i++) {
        var selected = sample.selectedThreads().get(i);
        ids[i] = selected.threadId();
        groups[i] = selected.group();
        available[i] = selected.cpuNanos() != null;
        cpuNanos[i] = available[i] ? selected.cpuNanos() : -1;
      }
      Logger.recordOutput("MatchPerformance/Resources/ThreadIds", ids);
      Logger.recordOutput("MatchPerformance/Resources/ThreadGroups", groups);
      Logger.recordOutput("MatchPerformance/Resources/ThreadCpuNanos", cpuNanos);
      Logger.recordOutput("MatchPerformance/Resources/ThreadCpuValueAvailable", available);
      Logger.recordOutput(
          "MatchPerformance/Resources/MainThreadCpuAvailable",
          sample.currentThreadCpuNanos() != null);
      if (sample.currentThreadCpuNanos() != null) {
        Logger.recordOutput(
            "MatchPerformance/Resources/MainThreadCpuNanos", sample.currentThreadCpuNanos());
      }
    }
  }

  private void updateGcStats() {
    long time = 0;
    long count = 0;
    for (int i = 0; i < gcBeans.size(); i++) {
      long currentTime = gcBeans.get(i).getCollectionTime();
      long currentCount = gcBeans.get(i).getCollectionCount();
      time += currentTime - lastGcTimes[i];
      count += currentCount - lastGcCounts[i];
      lastGcTimes[i] = currentTime;
      lastGcCounts[i] = currentCount;
    }
    Logger.recordOutput("LoggedRobot/GCTimeMS", (double) time);
    Logger.recordOutput("LoggedRobot/GCCounts", (double) count);
  }

  @Override
  public void endCompetition() {
    stopping = true;
    NotifierJNI.stopNotifier(observerNotifier);
    super.endCompetition();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      endCompetition();
      NotifierJNI.cleanNotifier(observerNotifier);
      super.close();
    }
  }
}
