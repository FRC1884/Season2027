package org.Griffins1884.frc2027.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.path.GoalEndState;
import com.pathplanner.lib.path.IdealStartingState;
import com.pathplanner.lib.path.PathConstraints;
import com.pathplanner.lib.path.PathPlannerPath;
import edu.wpi.first.hal.AllianceStationID;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.DataLogManager;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import edu.wpi.first.wpilibj.simulation.JoystickSim;
import edu.wpi.first.wpilibj.simulation.SimHooks;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.Commands;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.runtime.RuntimeModeProfile;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;
import org.littletonrobotics.junction.Logger;

/** Desktop-only composition. This class is excluded from the deployed JAR. */
public final class DesktopMatchMain {
  private DesktopMatchMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !args[0].equals("--config") || !args[2].equals("--output"))
      throw new IllegalArgumentException(
          "Usage: DesktopMatchMain --config absolute.json --output absolute-directory");
    ObjectMapper json = new ObjectMapper();
    Path output = Path.of(args[3]).toAbsolutePath();
    DesktopScenario scenario = DesktopScenario.fromJson(json.readTree(Path.of(args[1]).toFile()));
    Files.createDirectories(output);
    boolean stepped = scenario.timing().equals("stepped");
    // HAL must be queried before loading GlobalConstants, Robot or any actuator composition.
    if (!HAL.initialize(500, 0)) throw new IllegalStateException("HAL initialization failed");
    if (RobotBase.isReal()) throw new IllegalStateException("DESKTOP_MATCH_SIM refuses real HAL");
    if (stepped) {
      SimHooks.pauseTiming();
      SimHooks.restartTiming();
      SimHooks.pauseTiming();
      SimHooks.stepTimingAsync(1.0);
    }
    System.setProperty("frc.mode", "sim");
    System.setProperty("frc.web.bind", "127.0.0.1");
    System.setProperty("frc.web.port", "0");
    Files.createDirectories(output.resolve("logs"));
    System.setProperty("frc.performance.logDirectory", output.resolve("logs").toString());
    NetworkTableInstance nt = NetworkTableInstance.getDefault();
    nt.startServer(
        output.resolve("networktables.json").toString(), "127.0.0.1", 0, scenario.ntPort());
    DriverStationSim.resetData();
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setAllianceStationId(AllianceStationID.Red1);
    DriverStationSim.setEnabled(false);
    DriverStationSim.notifyNewData();
    RuntimeModeManager.setActiveProfile(
        new RuntimeModeProfile(
            scenario.profile().equals("HARD_DEBUG")
                ? GlobalConstants.LoggingMode.DEBUG
                : GlobalConstants.LoggingMode.COMP,
            false,
            Set.of(),
            null,
            null));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (DesktopRobot robot = new DesktopRobot(scenario, output)) {
      Map<String, Object> ready = new LinkedHashMap<>();
      ready.put("httpPort", robot.httpPort());
      ready.put("ntPort", scenario.ntPort());
      ready.put("pid", ProcessHandle.current().pid());
      ready.put("periodSeconds", robot.getPeriod());
      ready.put("odometryTargetHz", GlobalConstants.ODOMETRY_FREQUENCY);
      ready.put("platform", "DESKTOP_MATCH_SIM");
      ready.put("timing", scenario.timing());
      writeJsonAtomically(json, output.resolve("desktop-ready.json"), ready);
      if (scenario.waitForStart()) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(output.resolve("start.marker"))) {
          if (System.nanoTime() > limit)
            throw new IllegalStateException("Timed out waiting for host start.marker");
          Thread.sleep(20);
        }
      }
      Thread loop =
          new Thread(
              () -> {
                try {
                  robot.startCompetition();
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "DesktopMatchRobot");
      loop.start();
      loop.join((long) ((scenario.durationSeconds() + 30) * 1000));
      if (loop.isAlive()) {
        robot.endCompetition();
        loop.join(5000);
        throw new IllegalStateException("Desktop robot did not complete finite timeline");
      }
      try {
        robot.finishOutput();
      } catch (Exception error) {
        failure.compareAndSet(null, error);
      }
      Map<String, Object> evidence = robot.evidence(failure.get());
      writeJsonAtomically(json, output.resolve("desktop-evidence.json"), evidence);
      if (!"PASS".equals(evidence.get("functional_correctness")) && failure.get() == null)
        failure.set(
            new IllegalStateException(
                "Desktop workload phase validation failed; see desktop-evidence.json"));
      if (failure.get() != null)
        throw new IllegalStateException("Desktop robot failed", failure.get());
    } finally {
      DriverStationSim.setEnabled(false);
      DriverStationSim.notifyNewData();
      CommandScheduler.getInstance().cancelAll();
      CommandScheduler.getInstance().unregisterAllSubsystems();
      DriverStationSim.resetData();
      DriverStationSim.notifyNewData();
      if (stepped) SimHooks.resumeTiming();
      RuntimeModeManager.resetToDefaults();
      Logger.end();
      DataLogManager.stop();
      nt.stopServer();
    }
  }

  private static void writeJsonAtomically(ObjectMapper json, Path target, Object value)
      throws Exception {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    json.writeValue(temporary.toFile(), value);
    Files.move(
        temporary,
        target,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private static final class DesktopRobot extends ObservedRobot {
    private final DesktopScenario scenario;
    private final JoystickSim joystick = new JoystickSim(0);
    private final PhaseEvidence[] phaseEvidence;
    private final FrameWriter writer;
    private DesktopScenario.PhasePosition phase;
    private DesktopScenario.Inputs inputs = new DesktopScenario.Inputs(0, 0, 0, 0);
    private long firstNano;
    private long commandExecutions;
    private long driveCommandExecutions;
    private long autonomousCommandExecutions;
    private long driverCommandExecutions;
    private long loopCount;
    private long previousConsumedSamples;
    private long previousAcquiredSamples;
    private long previousAppliedDriveRequests;
    private volatile boolean complete;
    private Pose2d previousPose;

    DesktopRobot(DesktopScenario scenario, Path output) throws Exception {
      super(
          scenario.recording(),
          scenario.detailed(),
          scenario.durationSeconds() + 5,
          scenario.timing().equals("stepped")
              ? DesktopFixtureIO::createContainer
              : org.Griffins1884.frc2027.RobotContainer::new);
      this.scenario = scenario;
      if (scenario.timing().equals("stepped")
          && getPeriod() != org.littletonrobotics.junction.LoggedRobot.defaultPeriodSecs)
        throw new IllegalStateException("Fixture period differs from application period");
      phaseEvidence = new PhaseEvidence[scenario.phases().size()];
      for (int i = 0; i < phaseEvidence.length; i++) phaseEvidence[i] = new PhaseEvidence();
      phase = scenario.at(0);
      joystick.setAxisCount(6);
      joystick.setButtonCount(10);
      joystick.setPOVCount(1);
      DriverStationSim.setJoystickType(0, 1);
      DriverStationSim.setJoystickIsXbox(0, true);
      getRobotContainer().getDrive().setPerformanceObservationEnabled(true);
      CommandScheduler.getInstance()
          .onCommandExecute(
              command -> {
                commandExecutions++;
                if (command.getRequirements().contains(getRobotContainer().getDrive()))
                  driveCommandExecutions++;
                if (command.getName().equals("DesktopPathPlannerFixture"))
                  autonomousCommandExecutions++;
                else if (command.getRequirements().contains(getRobotContainer().getDrive())
                    && DriverStation.isTeleopEnabled()) driverCommandExecutions++;
              });
      previousPose = getRobotContainer().getDrive().getPose();
      writer = new FrameWriter(output);
    }

    @Override
    protected boolean useNotifierTiming() {
      return !scenario.timing().equals("stepped");
    }

    @Override
    protected void beforeObservedLoop(long cycle) {
      if (scenario.timing().equals("stepped") && cycle > 0) SimHooks.stepTimingAsync(getPeriod());
      if (firstNano == 0) firstNano = System.nanoTime();
      double elapsed =
          scenario.timing().equals("stepped")
              ? cycle * getPeriod()
              : (System.nanoTime() - firstNano) / 1e9;
      phase = scenario.at(elapsed);
      if (phase.name().equals("complete")) {
        complete = true;
        DriverStationSim.setEnabled(false);
        DriverStationSim.notifyNewData();
        endCompetition();
        return;
      }
      boolean enabled = scenario.enabled(phase);
      boolean autonomous = enabled && Set.of("autonomous", "auto_fixture").contains(phase.name());
      inputs =
          enabled && !autonomous
              ? scenario.inputs(elapsed)
              : new DesktopScenario.Inputs(0, 0, 0, 0);
      joystick.setRawAxis(0, -inputs.strafe());
      joystick.setRawAxis(1, -inputs.forward());
      joystick.setRawAxis(4, -inputs.rotate());
      joystick.setRawAxis(2, inputs.robotRelative());
      DriverStationSim.setDsAttached(true);
      DriverStationSim.setEnabled(
          enabled && !(scenario.fault().equals("blocked_teleop") && phase.name().equals("teleop")));
      DriverStationSim.setAutonomous(autonomous);
      DriverStationSim.setTest(false);
      DriverStationSim.setMatchTime(Math.max(0, scenario.durationSeconds() - elapsed));
      DriverStationSim.notifyNewData();
    }

    @Override
    protected String observationPhase() {
      return phase == null ? "startup" : phase.name();
    }

    @Override
    protected void afterObservedLoop(LoopSample sample) {
      if (phase.index() >= phaseEvidence.length) {
        return;
      }
      loopCount++;
      SwerveSubsystem drive = getRobotContainer().getDrive();
      var snapshot = drive.getPerformanceSnapshot();
      PhaseEvidence evidence = phaseEvidence[phase.index()];
      evidence.loops++;
      if (DriverStation.isEnabled()) evidence.enabledLoops++;
      boolean intendedAuto =
          Set.of("autonomous", "auto_fixture").contains(phase.name()) && scenario.enabled(phase);
      if (DriverStation.isEnabled() == scenario.enabled(phase)
          && (!scenario.enabled(phase)
              || (intendedAuto
                  ? DriverStation.isAutonomousEnabled()
                  : DriverStation.isTeleopEnabled()))) evidence.correctModeLoops++;
      if (Math.abs(inputs.forward()) + Math.abs(inputs.strafe()) + Math.abs(inputs.rotate()) > 0.01)
        evidence.nonzeroInputLoops++;
      if (snapshot.ready()) evidence.readyLoops++;
      if (snapshot.gyroConnected()) evidence.gyroConnectedLoops++;
      Pose2d pose = drive.getPose();
      double distance = pose.getTranslation().getDistance(previousPose.getTranslation());
      // Exclude the first sample after a disabled/reset phase transition from motion evidence.
      if (evidence.loops > 1) evidence.distanceMeters += distance;
      previousPose = pose;
      var states = drive.getValidationModuleSamples();
      for (var module : states) {
        evidence.maxMeasuredSpeed =
            Math.max(evidence.maxMeasuredSpeed, Math.abs(module.actualSpeedMetersPerSec()));
        evidence.maxDesiredSpeed =
            Math.max(evidence.maxDesiredSpeed, Math.abs(module.desiredSpeedMetersPerSec()));
        evidence.maxDriveVoltage =
            Math.max(evidence.maxDriveVoltage, Math.abs(module.driveVoltage()));
      }
      evidence.commandExecutions += commandExecutions;
      evidence.driveCommandExecutions += driveCommandExecutions;
      evidence.autonomousCommandExecutions += autonomousCommandExecutions;
      evidence.driverCommandExecutions += driverCommandExecutions;
      driverCommandExecutions = 0;
      commandExecutions = 0;
      driveCommandExecutions = 0;
      autonomousCommandExecutions = 0;
      evidence.acquiredSamples += snapshot.acquiredSamples() - previousAcquiredSamples;
      previousAcquiredSamples = snapshot.acquiredSamples();
      evidence.consumedSamples += snapshot.consumedSamples() - previousConsumedSamples;
      evidence.appliedDriveRequests +=
          snapshot.appliedDriveRequests() - previousAppliedDriveRequests;
      previousConsumedSamples = snapshot.consumedSamples();
      previousAppliedDriveRequests = snapshot.appliedDriveRequests();
      writer.offer(
          new Frame(
              sample,
              snapshot,
              scenario.timing().equals("stepped") ? -1 : simulationNanos(),
              latestResources(),
              getLastSchedulerNanos(),
              getLastContainerNanos(),
              scenario.timing().equals("stepped") ? pose : null,
              scenario.timing().equals("stepped") ? states : null));
    }

    @Override
    public void simulationPeriodic() {
      if (!scenario.timing().equals("stepped")) super.simulationPeriodic();
    }

    @Override
    protected Command createAutonomousCommand() {
      if (scenario.fault().equals("empty_auto")) return Commands.none();
      var drive = getRobotContainer().getDrive();
      // Only this desktop source set constructs synthetic paths; deployed assets stay untouched.
      return Commands.defer(
              () -> {
                Pose2d start = drive.getPose();
                double direction = Math.sin(start.getX()) >= 0 ? 1 : -1;
                Rotation2d heading = direction > 0 ? Rotation2d.kZero : Rotation2d.kPi;
                PathPlannerPath path =
                    new PathPlannerPath(
                        PathPlannerPath.waypointsFromPoses(
                            new Pose2d(start.getTranslation(), heading),
                            new Pose2d(
                                start.getX() + direction * 1.5, start.getY() + 0.3, heading)),
                        new PathConstraints(1.5, 1.5, 2.0, 2.0),
                        new IdealStartingState(0, start.getRotation()),
                        new GoalEndState(0, start.getRotation()));
                path.preventFlipping = true;
                return AutoBuilder.followPath(path);
              },
              Set.of(drive))
          .repeatedly()
          .withName("DesktopPathPlannerFixture");
    }

    int httpPort() {
      return getRobotContainer().getOperatorBoardServer().getPort();
    }

    void finishOutput() throws Exception {
      writer.finish();
    }

    Map<String, Object> evidence(Throwable failure) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("platform", "DESKTOP_MATCH_SIM");
      result.put("timing", scenario.timing());
      result.put("recording", scenario.recording());
      result.put(
          "io_model",
          scenario.timing().equals("stepped") ? "DETERMINISTIC_IDEAL_SENSOR_FIXTURE" : "MAPLESIM");
      result.put("completed", complete);
      result.put("loops", loopCount);
      result.put("droppedFrames", writer.dropped);
      result.put("writerFailure", writer.failure == null ? null : writer.failure.toString());
      result.put("failure", failure == null ? null : failure.toString());
      result.put(
          "autonomousSource", "desktop-only PathPlanner fixture; no deployed autonomous routine");
      result.put(
          "physics",
          scenario.timing().equals("stepped")
              ? "Deterministic ideal sensor fixture; no MapleSim, motor firmware, CAN or physical dynamics. drive voltage field is commanded feedforward proxy."
              : "MapleSim cost included in full loop and reported separately; library noise is not seedable");
      result.put("configuration", getRobotContainer().getDrive().getPerformanceSnapshot());
      List<Map<String, Object>> phases = new ArrayList<>();
      for (int i = 0; i < phaseEvidence.length; i++) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", i);
        item.put("name", scenario.phases().get(i).name());
        item.put("durationSeconds", scenario.phases().get(i).durationSeconds());
        item.put("evidence", phaseEvidence[i]);
        phases.add(item);
      }
      result.put("phases", phases);
      List<String> validityFailures = new ArrayList<>();
      for (int i = 0; i < phaseEvidence.length; i++) {
        for (String reason :
            DesktopWorkloadChecks.failures(
                scenario.profile(),
                scenario.phases().get(i).name(),
                phaseEvidence[i],
                !scenario.timing().equals("stepped")))
          validityFailures.add(i + ":" + scenario.phases().get(i).name() + ":" + reason);
      }
      result.put("phase_validity_failures", validityFailures);
      result.put("injected_fault", scenario.fault());
      result.put("capture_complete", complete && writer.failure == null && writer.dropped == 0);
      result.put("dropped_records", writer.dropped);
      result.put("logger_queue_fault_cycles", loggerQueueFaultCycles());
      result.put("expected_loop_samples", loopCount);
      var configuration = getRobotContainer().getDrive().getPerformanceSnapshot();
      Map<String, Object> workload = new LinkedHashMap<>();
      workload.put(
          "phases_reached", java.util.Arrays.stream(phaseEvidence).allMatch(p -> p.loops > 0));
      workload.put(
          "commands_executed",
          scenario.profile().startsWith("IDLE")
              || java.util.Arrays.stream(phaseEvidence)
                  .anyMatch(p -> p.driveCommandExecutions > 0));
      workload.put("sensor_samples", configuration.acquiredSamples());
      workload.put("odometry_samples", configuration.consumedSamples());
      workload.put(
          "readiness",
          java.util.Arrays.stream(phaseEvidence).allMatch(p -> p.readyLoops == p.loops));
      workload.put(
          "drive_inputs",
          java.util.Arrays.stream(phaseEvidence).anyMatch(p -> p.nonzeroInputLoops > 0));
      workload.put(
          "output_changes",
          java.util.Arrays.stream(phaseEvidence)
              .anyMatch(
                  p ->
                      p.maxDesiredSpeed > 0.01
                          && (scenario.timing().equals("stepped") || p.maxDriveVoltage > 0.01)));
      workload.put(
          "pose_evolved",
          java.util.Arrays.stream(phaseEvidence)
              .anyMatch(p -> p.distanceMeters > 0.001 && p.maxMeasuredSpeed > 0.01));
      workload.put("phase_obligations", validityFailures.isEmpty());
      result.put("workload_evidence", workload);
      result.put(
          "functional_correctness",
          configuration.invalidSnapshots() == 0
                  && configuration.ready()
                  && failure == null
                  && validityFailures.isEmpty()
              ? "PASS"
              : "FAIL");
      return result;
    }

    @Override
    public void close() {
      endCompetition();
      try {
        if (writer != null) writer.finish();
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      } finally {
        super.close();
      }
    }
  }

  public static final class PhaseEvidence {
    public long loops,
        enabledLoops,
        correctModeLoops,
        acquiredSamples,
        driverCommandExecutions,
        nonzeroInputLoops,
        readyLoops,
        gyroConnectedLoops,
        commandExecutions,
        driveCommandExecutions,
        autonomousCommandExecutions,
        consumedSamples,
        appliedDriveRequests;
    public double distanceMeters, maxMeasuredSpeed, maxDesiredSpeed, maxDriveVoltage;
  }

  record Frame(
      LoopSample sample,
      SwerveSubsystem.PerformanceSnapshot drive,
      long physicsNanos,
      ResourceProbe.Snapshot resources,
      long schedulerNanos,
      long containerNanos,
      Pose2d pose,
      SwerveSubsystem.ValidationModuleSample[] modules) {
    Frame(
        LoopSample sample,
        SwerveSubsystem.PerformanceSnapshot drive,
        long physicsNanos,
        ResourceProbe.Snapshot resources,
        long schedulerNanos,
        long containerNanos) {
      this(sample, drive, physicsNanos, resources, schedulerNanos, containerNanos, null, null);
    }
  }

  /** Only this bounded drainer performs CSV formatting or file writes. */
  static final class FrameWriter {
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(4096);
    final Thread thread;
    private volatile boolean done;
    private volatile Throwable failure;
    private long dropped;

    FrameWriter(Path output) {
      thread =
          new Thread(
              () -> {
                try (BufferedWriter loops =
                        Files.newBufferedWriter(output.resolve("loop-samples.csv"));
                    BufferedWriter scopes =
                        Files.newBufferedWriter(output.resolve("subsystem-timings.csv"));
                    BufferedWriter resources =
                        Files.newBufferedWriter(output.resolve("resource-samples.csv"));
                    BufferedWriter threadResources =
                        Files.newBufferedWriter(output.resolve("thread-resource-samples.csv"));
                    BufferedWriter trace =
                        Files.newBufferedWriter(output.resolve("control-trace.csv"))) {
                  loops.write(
                      "cycle,phase,start_ns,end_ns,original_due_us,effective_due_us,start_us,end_us,period_us,interval_ns,skipped_releases,execution_ms,lateness_ms,headroom_ms,deadline_miss,execution_overrun\n");
                  scopes.write("cycle,phase,scope,elapsed_ms,inclusive\n");
                  resources.write(
                      "monotonic_ns,process_cpu_ns,current_thread_cpu_ns,heap_used_bytes,heap_committed_bytes,heap_max_bytes,threads,available_processors,gc_collections,gc_time_ms\n");
                  threadResources.write("monotonic_ns,thread_id,group,cpu_ns\n");
                  trace.write(
                      "cycle,phase,x_m,y_m,yaw_rad,module,desired_speed_mps,measured_speed_mps,desired_angle_rad,measured_angle_rad,command_voltage\n");
                  long lastResource = -1;
                  while (!done || !queue.isEmpty()) {
                    Frame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    LoopSample s = frame.sample();
                    loops.write(
                        s.cycle()
                            + ","
                            + s.phase()
                            + ","
                            + s.startNanos()
                            + ","
                            + s.endNanos()
                            + ","
                            + s.originalDueUs()
                            + ","
                            + s.effectiveDueUs()
                            + ","
                            + s.startUs()
                            + ","
                            + s.endUs()
                            + ","
                            + s.periodUs()
                            + ","
                            + s.intervalNanos()
                            + ","
                            + s.skippedReleases()
                            + ","
                            + s.executionMs()
                            + ","
                            + s.latenessMs()
                            + ","
                            + s.headroomMs()
                            + ","
                            + s.deadlineMiss()
                            + ","
                            + s.executionOverrun()
                            + "\n");
                    var resource = frame.resources();
                    if (resource != null && resource.monotonicNanos() != lastResource) {
                      lastResource = resource.monotonicNanos();
                      resources.write(
                          resource.monotonicNanos()
                              + ","
                              + nullable(resource.processCpuNanos())
                              + ","
                              + nullable(resource.currentThreadCpuNanos())
                              + ","
                              + resource.heapUsedBytes()
                              + ","
                              + resource.heapCommittedBytes()
                              + ","
                              + resource.heapMaxBytes()
                              + ","
                              + resource.threads()
                              + ","
                              + resource.availableProcessors()
                              + ","
                              + nullable(resource.gcCollections())
                              + ","
                              + nullable(resource.gcTimeMillis())
                              + "\n");
                      for (var threadSample : resource.selectedThreads()) {
                        threadResources.write(
                            resource.monotonicNanos()
                                + ","
                                + threadSample.threadId()
                                + ","
                                + threadSample.group()
                                + ","
                                + nullable(threadSample.cpuNanos())
                                + "\n");
                      }
                    }
                    scope(scopes, s, "Command scheduler", frame.schedulerNanos(), true);
                    scope(scopes, s, "RobotContainer", frame.containerNanos(), true);
                    if (frame.pose() != null) {
                      for (var module : frame.modules()) {
                        trace.write(
                            s.cycle()
                                + ","
                                + s.phase()
                                + ","
                                + frame.pose().getX()
                                + ","
                                + frame.pose().getY()
                                + ","
                                + frame.pose().getRotation().getRadians()
                                + ","
                                + module.index()
                                + ","
                                + module.desiredSpeedMetersPerSec()
                                + ","
                                + module.actualSpeedMetersPerSec()
                                + ","
                                + module.desiredAngleRadians()
                                + ","
                                + module.actualAngleRadians()
                                + ","
                                + module.driveVoltage()
                                + "\n");
                      }
                    }
                    var d = frame.drive();
                    scope(scopes, s, "Swerve periodic", d.periodicNanos(), true);
                    scope(scopes, s, "Odometry lock wait", d.lockWaitNanos(), false);
                    scope(scopes, s, "Odometry lock hold", d.lockHoldNanos(), true);
                    scope(scopes, s, "Odometry processing", d.odometryNanos(), true);
                    scope(scopes, s, "Module input processing", d.inputProcessingNanos(), true);
                    scope(scopes, s, "Setpoint generation", d.setpointNanos(), false);
                    scope(scopes, s, "Motor control path", d.controlNanos(), true);
                    if (frame.physicsNanos() >= 0)
                      scope(scopes, s, "Desktop MapleSim physics", frame.physicsNanos(), true);
                  }
                } catch (Throwable error) {
                  failure = error;
                }
              },
              "DesktopEvidenceWriter");
      thread.start();
    }

    private static String nullable(Long value) {
      return value == null ? "" : value.toString();
    }

    void offer(Frame frame) {
      if (!queue.offer(frame)) dropped++;
    }

    void finish() throws Exception {
      done = true;
      thread.join(5000);
      if (thread.isAlive()) {
        thread.interrupt();
        throw new IllegalStateException("Evidence writer did not stop");
      }
      if (failure != null) throw new IllegalStateException("Evidence writer failed", failure);
    }

    private static void scope(
        BufferedWriter writer, LoopSample sample, String scope, long nanos, boolean inclusive)
        throws Exception {
      writer.write(
          sample.cycle()
              + ","
              + sample.phase()
              + ","
              + scope
              + ","
              + nanos / 1e6
              + ","
              + inclusive
              + "\n");
    }
  }
}
