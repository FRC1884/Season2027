package org.Griffins1884.frc2027.subsystems.swerve;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.hal.HAL;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.simulation.DriverStationSim;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.runtime.RuntimeModeProfile;
import org.Griffins1884.frc2027.util.LoggedTunableNumber;
import org.junit.jupiter.api.*;
import org.littletonrobotics.junction.networktables.LoggedNetworkNumber;

class ModuleConfigurationIntegrationTest {
  private RuntimeModeProfile previousProfile;
  private boolean previousEnabled;
  private boolean previousDsAttached;

  @BeforeAll
  static void initializeHal() {
    assertTrue(HAL.initialize(500, 0));
  }

  @BeforeEach
  void setup() {
    previousProfile = RuntimeModeManager.getActiveProfile();
    previousEnabled = DriverStation.isEnabled();
    previousDsAttached = DriverStation.isDSAttached();
    RuntimeModeManager.setActiveProfile(
        new RuntimeModeProfile(GlobalConstants.LoggingMode.COMP, false, Set.of(), null, null));
    enabled(false);
  }

  @AfterEach
  void restore() {
    DriverStationSim.setEnabled(previousEnabled);
    DriverStationSim.setDsAttached(previousDsAttached);
    DriverStationSim.notifyNewData();
    DriverStation.refreshData();
    RuntimeModeManager.setActiveProfile(previousProfile);
  }

  private static void enabled(boolean enabled) {
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setEnabled(enabled);
    DriverStationSim.notifyNewData();
    DriverStation.refreshData();
    assertEquals(enabled, DriverStation.isEnabled());
  }

  @Test
  void selectedInitialGainsAreAppliedBeforeOutputsWithNoUnchangedReapplication() {
    try (var worker = new ModuleConfigurationWorker(DriverStation::isDisabled)) {
      FakeIO[] ios = modules(worker, (gains, brake) -> "");
      SwerveSubsystem drive = drive(ios);
      try {
        var expected =
            new ModuleConfiguration(
                SwerveConstants.KRAKEN_DRIVE_TORQUE_GAINS.kP().get(),
                0,
                SwerveConstants.KRAKEN_DRIVE_TORQUE_GAINS.kD().get(),
                SwerveConstants.KRAKEN_TURN_TORQUE_GAINS.kP().get(),
                0,
                SwerveConstants.KRAKEN_TURN_TORQUE_GAINS.kD().get());
        for (FakeIO io : ios) {
          assertEquals(List.of(expected), io.applied);
          assertTrue(io.isConfigurationReady());
        }
        for (int cycle = 0; cycle < 5; cycle++) drive.periodic();
        for (FakeIO io : ios) {
          assertEquals(1, io.applied.size());
          assertEquals(0, io.requests);
        }
      } finally {
        drive.close();
      }
    }
  }

  @Test
  void stalledConfigurationDoesNotBlockPeriodicOrOdometryAndEnableLatchesAllOutputs()
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    var worker = new ModuleConfigurationWorker(DriverStation::isDisabled);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    FakeIO[] ios =
        modules(
            worker,
            (gains, brake) -> {
              if (calls.incrementAndGet() == 5) {
                entered.countDown();
                release.await();
              }
              return "";
            });
    SwerveSubsystem drive = drive(ios);
    try {
      drive.periodic();
      enabled(true);
      drive.runCharacterization(2.0);
      for (FakeIO io : ios) assertEquals(2.0, io.driveOutput);
      enabled(false);
      assertTrue(ios[0].handle.request(new ModuleConfiguration(1, 2, 3, 4, 5, 6)));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // The backend is still blocked. Completion, not elapsed execution time, is the assertion.
      executor.submit(drive::periodic).get(5, TimeUnit.SECONDS);
      assertEquals(1, release.getCount());
      assertTrue(
          executor
              .submit(
                  () -> {
                    SwerveSubsystem.odometryLock.lock();
                    try {
                      return true;
                    } finally {
                      SwerveSubsystem.odometryLock.unlock();
                    }
                  })
              .get(5, TimeUnit.SECONDS));
      enabled(true);
      drive.periodic();
      int[] before = java.util.Arrays.stream(ios).mapToInt(io -> io.nonzeroRequests).toArray();
      drive.runVelocity(new ChassisSpeeds(1, 0, 0));
      drive.runCharacterization(3.0);
      drive.runTurnCharacterization(3.0);
      assertInhibited(ios, before);
      release.countDown();
      worker.awaitIdleForTest();
      drive.periodic();
      drive.runCharacterization(3.0);
      assertInhibited(ios, before);
      enabled(false);
      drive.periodic();
      enabled(true);
      drive.periodic();
      drive.runCharacterization(3.0);
      for (FakeIO io : ios) assertEquals(3.0, io.driveOutput);
    } finally {
      release.countDown();
      worker.close();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      drive.close();
    }
  }

  @Test
  void rejectedEnableRaceRetriesDesiredGainsAndAllGainChecksAdvance() throws Exception {
    RuntimeModeManager.setActiveProfile(
        new RuntimeModeProfile(GlobalConstants.LoggingMode.COMP, true, Set.of(), null, null));
    List<LoggedTunableNumber> numbers = new ArrayList<>();
    List<LoggedNetworkNumber> inputs = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (String name :
        new String[] {
          "krakenDrivekS",
          "krakenDrivekV",
          "krakenDrivekP",
          "krakenDrivekD",
          "krakenTurnkP",
          "krakenTurnkD"
        }) {
      Field field = Module.class.getDeclaredField(name);
      field.setAccessible(true);
      var number = (LoggedTunableNumber) field.get(null);
      number.get();
      Field dashboard = LoggedTunableNumber.class.getDeclaredField("dashboardNumber");
      dashboard.setAccessible(true);
      var input = (LoggedNetworkNumber) dashboard.get(number);
      numbers.add(number);
      inputs.add(input);
      values.add(number.get());
    }
    try (var worker = new ModuleConfigurationWorker(DriverStation::isDisabled)) {
      FakeIO io = new FakeIO(worker, (gains, brake) -> "");
      Module module = new Module(io, 0);
      try {
        for (int i = 0; i < inputs.size(); i++) {
          inputs.get(i).set(values.get(i) + i + 1);
          inputs.get(i).periodic();
        }
        io.rejectWithEnable = true;
        module.updateConfiguration(true);
        assertEquals(1, io.requests);
        assertEquals(1, io.handle.status().desiredRevision());
        for (LoggedTunableNumber number : numbers)
          assertFalse(number.hasChanged(module.hashCode()));
        enabled(false);
        module.updateConfiguration(true);
        worker.awaitIdleForTest();
        module.updateConfiguration(true);
        assertEquals(2, io.requests);
        assertEquals(2, io.applied.size());
        assertEquals(
            new ModuleConfiguration(
                values.get(2) + 3, 0, values.get(3) + 4, values.get(4) + 5, 0, values.get(5) + 6),
            io.applied.get(1));
        module.updateConfiguration(true);
        assertEquals(2, io.requests);
      } finally {
        module.close();
      }
    } finally {
      for (int i = 0; i < inputs.size(); i++) {
        inputs.get(i).set(values.get(i));
        inputs.get(i).periodic();
      }
    }
  }

  private static void assertInhibited(FakeIO[] ios, int[] before) {
    for (int i = 0; i < ios.length; i++) {
      assertEquals(0.0, ios[i].driveOutput);
      assertEquals(0.0, ios[i].turnOutput);
      assertEquals(before[i], ios[i].nonzeroRequests);
    }
  }

  private static FakeIO[] modules(
      ModuleConfigurationWorker worker, ModuleConfigurationWorker.Backend backend) {
    return new FakeIO[] {
      new FakeIO(worker, backend),
      new FakeIO(worker, backend),
      new FakeIO(worker, backend),
      new FakeIO(worker, backend)
    };
  }

  private static SwerveSubsystem drive(FakeIO[] ios) {
    return new SwerveSubsystem(
        new GyroIO() {}, ios[0], ios[1], ios[2], ios[3], () -> 1.0, () -> 0.05);
  }

  private static final class FakeIO implements ModuleIO {
    final ModuleConfigurationWorker.Handle handle;
    final List<ModuleConfiguration> applied = new CopyOnWriteArrayList<>();
    int requests;
    int nonzeroRequests;
    boolean rejectWithEnable;
    double driveOutput;
    double turnOutput;

    FakeIO(ModuleConfigurationWorker worker, ModuleConfigurationWorker.Backend backend) {
      handle =
          worker.register(
              (gains, brake) -> {
                applied.add(gains);
                return backend.apply(gains, brake);
              });
    }

    @Override
    public void initializeConfiguration(ModuleConfiguration gains) {
      handle.initialize(gains);
    }

    @Override
    public boolean requestConfiguration(ModuleConfiguration gains) {
      requests++;
      if (rejectWithEnable) {
        rejectWithEnable = false;
        enabled(true);
      }
      return handle.request(gains);
    }

    @Override
    public void updateConfigurationState(boolean disabled) {
      handle.updateState(disabled);
    }

    @Override
    public boolean isConfigurationReady() {
      return handle.isReady();
    }

    @Override
    public ModuleConfigurationWorker.Status getConfigurationStatus() {
      return handle.status();
    }

    @Override
    public void updateInputs(ModuleIOInputs inputs, double timestamp) {
      inputs.driveConnected = true;
      inputs.turnConnected = true;
      inputs.odometryTimestamps = new double[] {timestamp};
      inputs.odometryDrivePositionsRad = new double[] {0};
      inputs.odometryTurnPositions = new Rotation2d[] {Rotation2d.kZero};
    }

    @Override
    public void setDriveOpenLoop(double output) {
      driveOutput = output;
      if (output != 0) nonzeroRequests++;
    }

    @Override
    public void setTurnOpenLoop(double output) {
      turnOutput = output;
      if (output != 0) nonzeroRequests++;
    }

    @Override
    public void setDriveVelocity(double velocity, double feedforward) {
      if (velocity != 0 || feedforward != 0) nonzeroRequests++;
    }
  }
}
