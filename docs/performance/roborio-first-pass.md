# roboRIO performance: first implementation pass

## Status and scope

Approved plan: revision 1. Risk: **HIGH**, because configuration readiness and
odometry/reset concurrency affect actuator behaviour. This report describes an
uncommitted implementation; plan agreement is not learning verification, independent
review, Safety Code Owner approval or hardware clearance.

- Integration base: `core-mechanisms-lead`,
  `55e9f524800143d2fa6fbc33c0b216404f69fbb8`.
- Audit context: `4deff6ff360b620d6fe45cfcae1c27b47fa9f1a7`.
  Robot source and dependencies are identical between these revisions.
- Initial workspace: `task/ci-build-repair` at
  `3440b0cfb77ae90715b8c86043433c77221b0304`, which predates the drivetrain.
  Work used an isolated `task/roborio-performance-first-pass` worktree; original
  untracked wrapper files and NetworkTables state were preserved.
- `software-leads` at `0e7ccf8425ad19d7eaa01a7e104cc8d1f6a7f0f5` was inspected.
  Its newer Harness changes were not imported into this product diff.
- No dashboard/frontend, autonomous/setpoint algorithm, hardware profile, CAN ID,
  bus assignment, gains/constants, dependency, Java, build, CI or governance changes.
- **No robot deployment or physical actuation was performed.** Fake IO and desktop
  physics simulation do not constitute robot operation.
- **Actual roboRIO execution-time improvement: NOT MEASURED.**

## Configuration and readiness

Before: constructors applied full motor configurations with zero PID slots;
`SwerveSubsystem.periodic -> odometryLock -> Module.periodic -> setDrivePID/setTurnPID`
performed full configurator writes, each with up to five 250 ms attempts. Short-circuit
change checks left later gains uninitialised. A separate brake executor wrote the same
mutable configurations without a common transaction owner.

After: `Module` captures existing selected initial gains and calls checked startup
configuration before normal outputs. Full motor configuration is applied with those
gains, then initial position resets are checked. Initial tracking is primed once.
Startup work runs outside the odometry lock. Desired values never prove acceptance.

Runtime path: main-thread tuning observation -> immutable request -> one drivetrain
worker -> checked motor transaction -> revision/status publication -> main-thread
readiness acknowledgement. Four module slots bound pending work; each slot retains
only its latest desired gains and brake/coast state. Both motors' writes are serial;
configuration objects belong to startup and then the worker. Duplicate requests do
not reapply. Old completions retain their own revision. Retries total at most five
backend attempts per revision, including transactions deferred across enable transitions.

New tuning applies only while disabled and permitted by the existing runtime profile.
Enabled edits are deferred, including software feedforward and simulation gains.
Disabling tuning retains the last applied configuration; it does not silently apply
default gains while operating. Re-enter disabled tuning or restart to change it.
A rejected request is retried after returning disabled; it is not falsely marked sent.
An incomplete/failed configuration inhibits the whole drivetrain, including drive,
turn characterization and X-lock requests. Enabling during configuration latches that
inhibit. Successful completion while enabled does not re-arm motion; a subsequent
fresh disabled acknowledgement and matching successful revision are required.

A failed revision exhausts its bounded retries and remains unready. Correcting the
requested values creates a new revision; persistent startup prerequisites require
repair and restart. There is no silent unlimited background retry. Status includes
requests, completions, attempts, duration, current errors and persistent failure count,
last failed revision and error, so a later success does not erase failure evidence.
The main thread logs status; the worker never calls AdvantageKit. Hardware readiness
means the configured transaction succeeded, not that the selected chassis is approved.

Normal motor `setControl` calls remain on the existing control path. Shutdown stops
and joins the configuration worker before closing devices, stops the odometry producer,
and unregisters the drivetrain. Joins occur only during lifecycle shutdown.

## Sensor snapshots, resets and measurement reuse

Before: the shared lock enclosed gain writes, gyro/module IO, derived module calculations
and telemetry. Estimator reset methods were not serialized with snapshot processing.
Every measured-speed accessor rebuilt module states and ran kinematics; wheel-radius
preferences were read repeatedly, including once per high-rate wheel sample.

After: the shared odometry lock encloses related gyro/module input and queue capture.
Configuration, logs, module conversions and estimator processing run outside it. A
separate measurement lock serializes capture/processing against reset and recovery;
it is never acquired by the 250 Hz producer. Snapshot generations reject reentrant
reset batches. Resets clear related queues together and invalidate producer refreshes
that began before the reset. Hardware yaw reset/refresh runs outside the shared lock.
Lock order is measurement lock -> odometry lock; registration retains signals lock ->
odometry lock. The producer does not acquire the measurement lock.

Queue capacity remains 20. On overflow every related channel drops the same sample;
partial appends are forbidden and counted. Malformed/nonfinite or misaligned batches
are rejected and counted instead of pairing unrelated indices. A connected gyro with
no samples retains the previous heading, as before. Valid sample order is preserved.
SIM adapters share one acquisition timestamp with the existing 4 ms substep offsets;
REAL empty-queue fallback samples share the acquisition timestamp too. Real queued
sample timestamps and latency correction are unchanged. The gyro fallback maintains
wheel history while connected and creates delta objects only while disconnected.

One radius preference read and one measured kinematics calculation normally serve a
cycle. Explicit calibration setters/clear operations increment a revision so immediate
speed/reset consumers refresh; external preference edits are picked up at the next
acquisition. Public speed results and measured states used by the setpoint generator
are copied, preserving ownership. Commanded state never substitutes for measurements.
Battery/CAN/brownout values are read once for the subsystem's equivalent diagnostic
consumers. Field filters and fault calculations still advance every cycle.

The unoptimized command inverse-kinematics calculation is intentionally retained in
its original position before setpoint generation: WPILib also updates heading history
there. Only its publication is gated. No setpoint algorithm was changed.

## Signal inventory and logging coverage

Rates are nominal at the unchanged 20 ms loop. DEBUG, subsystem debug, active SysId
and explicit characterization retain full derived telemetry. COMP derived output uses
a 100 ms cadence; initial publication, mode changes and clock rewind publish immediately.
Keys and types of existing signals remain unchanged. Fixed module keys are constructed
once. Gates run before telemetry-only arithmetic/string construction.

| Keys / family | Producer | Consumers / purpose | Before | After |
| --- | --- | --- | --- | --- |
| `Swerve/Module*/Inputs/*` scalar position, velocity, volts, current, connection | Module | Sensor/control diagnosis, SysId cross-check | 50 Hz | 50 Hz |
| `Swerve/Module*/AbsoluteAngleRad` | Module / CANcoder | Unique absolute sensor diagnosis | 50 Hz | 50 Hz |
| `Swerve/Gyro/{Connected,YawPosition,YawVelocityRadPerSec}` | Swerve | Gyro/control diagnosis | 50 Hz | 50 Hz |
| Module `Inputs/Odometry*`, gyro `OdometryYaw*` | Captured IO | Timestamp/sample diagnosis; future replay repair | Not explicitly recorded | Complete captured arrays every cycle |
| `AngleJumpDetected`, `AngleJumpCount`, connection alerts, observer issue/candidate fields | Module/Swerve | Faults and operator diagnosis | 50 Hz | 50 Hz; no slow fault gate |
| Module desired/actual speed/angle, error, ratio, `LastAngleDeltaRad` | Module | Derived diagnosis; no control consumption of published values | 50 Hz | COMP 10 Hz; DEBUG/characterization 50 Hz |
| Field velocity/acceleration outputs and sample age/dt | Swerve | Motion diagnostics | 50 Hz | COMP 10 Hz; validity/finite evaluation remains 50 Hz |
| `Swerve/Debug/*`, numeric `Swerve/Observer/*` | Swerve | Diagnostics; observer calculations also detect faults | 50 Hz | Publication COMP 10 Hz; necessary calculations stay 50 Hz |
| Calibration radius/zero trim | Swerve/Module | Configuration diagnosis | 50 Hz | Initially and changed |
| Calibration `AbsoluteAngleDeg` | Swerve | Duplicate angular-unit view | 50 Hz | COMP 10 Hz; unique absolute reading retained above |
| `SwerveStates/Measured`, `SwerveChassisSpeeds/Measured`, setpoints and optimized setpoints, `Odometry/Robot` | Swerve / AutoLog | Drive diagnosis, visualization | 50 Hz / command rate | Preserved; measured state reused |
| `SwerveStates/SetpointsUnoptimized` | Swerve | Debug view | Command rate | COMP cadence; calculation/order preserved |
| SysId motor records and phases | SysId / Swerve | Characterization analysis | Routine rate; some states DEBUG-only | Motor records preserved; active data full rate, state events also available in COMP |
| Configuration revisions/errors/failures | Module worker status, logged by main | Readiness and failure diagnosis | Retry outcomes not consistently exposed | Initially and changed; failure history and applied feedforward retained |
| DS log, warnings/errors | Robot / RobotLogging | Driver Station and faults | Existing runtime/event rate | Preserved |
| WPILOGWriter, NT4Publisher, URCL, DataLogManager NT capture | Robot / libraries | On-disk, network, device and NT-only signals | Existing receivers | All retained |

The autonomous observer still evaluates and latches at control rate. String formatting
for repeated warnings is limited to 0.5 seconds; issue/module transitions report without
waiting for that repeat interval. Unique NT-only sources include dashboard requests,
chooser/settings topics, external clients and library publishers. Full coverage
replacement is not established, so blanket NetworkTables capture removal is **DEFERRED**.
The deployed frontend subscribes to `/OperatorBoard/v1/ToDashboard/*`; this task does
not repair pre-existing missing producers or change that contract.

AdvantageKit 26.0.2 sends completed cloned tables through a bounded receiver queue.
WPILOG and NT4 omit unchanged non-timestamp values. Reducing `recordOutput` frequency
changes derived on-disk resolution as well as publication; constant keys did not
necessarily consume new disk/network records before. No proportional CPU, file-size
or network saving is inferred from reduced call counts.

REPLAY baseline limitation: RobotContainer supplies empty drivetrain IO and the
module/gyro paths do not hydrate inputs through `Logger.processInputs`. Additional
raw arrays improve capture, but **do not fix replay restoration**. End-to-end recorded
robot replay is **NOT RUN**; it cannot be claimed as working from no-op IO tests.
The existing GyroIOSim yaw-rate unit conversion is also unchanged and deferred.

## Instrumentation and evidence

Collection environment: macOS 26.5 arm64, Temurin 17.0.18, cached Gradle 8.11.
Resolved libraries: WPILib/GradleRIO 2026.2.1, AdvantageKit 26.0.2, Phoenix 26.3.0,
PathPlanner 2026.1.2, REVLib 2026.0.5, URCL/Studica 2026.0.0, maple-sim 0.3.14,
dyn4j 5.0.2. The untracked local Gradle 9.3 wrapper was not used or changed.

Existing AdvantageKit fields verified against 26.0.2 source:
`LoggedRobot/FullCycleMS`, `UserCodeMS`, `LogPeriodicMS`, `GCTimeMS`, `GCCounts`,
and `Logger/QueuedCycles`. `MS` fields are milliseconds; the latter two are counts.
Logger also provides its own dashboard/conduit/AutoLog/alert/radio/console stage timings.

New `*MS` fields use `System.nanoTime()` elapsed differences, never replay/control time:

- `Robot/Performance/SchedulerMS`, `ContainerMS`: adjacent robotPeriodic work.
- `Swerve/Performance/PeriodicMS`: inclusive subsystem total.
- `OdometryLockWaitMS`, `OdometryLockHoldMS`: distinct wait and hold intervals.
- `ModuleAcquisitionMS`: four nested input-call durations within the lock hold.
- `InputProcessingAndOdometryMS`: inclusive post-capture processing segment.
- `OdometryProcessingMS`: estimator batch validation/update within that segment.
- Per-module `Performance/TelemetryAndFaultEvaluationMS` and `OdometryConversionMS`:
  subsegments, not additional independent work to sum with their parent.
- Per-module configuration `LastDurationMS`: startup/worker transaction elapsed time,
  outside main-loop/odometry timings.

These are wall elapsed times, **not CPU usage**. Instrumentation itself has overhead.
Do not add nested metrics to their inclusive parents or sum worker time into user code.

| Evidence | Result | Interpretation |
| --- | --- | --- |
| Unmodified integration baseline build/format | PASS; 28 tests | No baseline test failures |
| New straight/reset/characterization fixtures on unchanged base | PASS; 3 tests | Behaviour locked before source changes |
| Fake-clock cadence capture | PASS; 5 derived publications in 25 cycles, 25 sensor captures | Actual AKit output table observations, no CPU claim |
| Radius/measurement cache tests | PASS; one acquisition read and one measured calculation; repeated consumers add none | Deterministic operation counts |
| Before/after command trace | PASS; 12 cycles, 216 scalar comparisons, tolerance 1e-9 | Fixture generated by executing base; command path not instrumented |
| Repeated measurement-only workload | Before 192 calculations; after 12, over 12 cycles with 11 consumers each | Separate counting kinematics fixture; no command-generator changes or timing claim |
| Configuration concurrency/failure tests | PASS; 12 tests | Includes stalled periodic/producer access and enable latch |
| Snapshot/reset/fallback tests | PASS; 8 tests | Includes concurrent reset and malformed samples |
| Physics simulation and queue tests | PASS; 3 tests | 20 simulation cycles, tuning deferral and bounded overflow |
| Final full build/tests/format | PASS; 68 tests, zero failures/errors/skips | Includes all 28 existing tests and 40 new tests |
| Desktop wall-time distributions / GC comparison | NOT RUN | No median/p95/p99/max/overrun figures collected |
| roboRIO timing / firmware / physical response | BLOCKED / NOT MEASURED | Hardware prerequisites below |
| Recorded robot replay | NOT RUN | Existing input restoration limitation |

Executed commands used the installed Gradle 8.11 executable (shown as `$GRADLE` below)
with `JAVA_HOME` selecting Temurin 17.0.18. No wrapper, dependency or build edits were made.

```sh
"$GRADLE" build spotlessCheck --no-daemon --console=plain
"$GRADLE" dependencies --configuration runtimeClasspath --no-daemon --console=plain
"$GRADLE" test --tests '*SwerveMeasurementRegressionTest' --no-daemon --console=plain
"$GRADLE" test --tests '*ModuleConfiguration*Test' --no-daemon --console=plain
"$GRADLE" test --tests '*SwerveSnapshotTest' --no-daemon --console=plain
"$GRADLE" test --tests '*SwerveSimulationSnapshotTest' --tests '*PhoenixOdometryQueueTest' --no-daemon --console=plain
SWERVE_TRACE_OUTPUT=/tmp/season2027-control-before.csv "$GRADLE" test --tests '*SwerveControlTraceTest' --no-daemon --console=plain
SWERVE_TRACE_OUTPUT=/tmp/season2027-control-after.csv "$GRADLE" test --tests '*SwerveControlTraceTest' --no-daemon --console=plain
"$GRADLE" spotlessApply build spotlessCheck --no-daemon --console=plain
git diff --check
```

The before-trace command ran in a separate checkout of the exact base with only the
same test fixture added; the after command ran in the implementation checkout.
The optional `SWERVE_TRACE_OUTPUT` variable captures fixtures; normal test execution
requires the checked-in baseline CSV and asserts numerical equivalence. Operation counts
have no JVM warm-up/time sensitivity. They are separate from the uninstrumented command trace.
Focused utility/logging tests also used temporary external Gradle init scripts to isolate
test compilation while other new test classes were being implemented; final validation
uses the unchanged project build. The three expected pre-fix tunable-test failures
(null late activation/redundant callbacks) were reproduced and resolved. Intermediate
new-test compile failures (incomplete concurrent classes and test-only API assumptions)
were resolved. Baseline Groovy tooling/joystick warnings did not fail tests.

## Changed files

All Java paths below are relative to `src/main/java/org/Griffins1884/frc2027/`.

| Files | Reason |
| --- | --- |
| `Robot.java`, `RobotContainer.java` | Stage timing and worker/device lifecycle |
| `subsystems/swerve/ModuleConfiguration.java`, `ModuleConfigurationWorker.java` | Immutable requests, serialized bounded work, readiness and persistent failures |
| `ModuleIO.java`, `ModuleIOFullKraken.java` | Checked startup, nonblocking requests, hardware output gates, shared fallback timestamp, cleanup |
| `Module.java` | Split capture/processing, cached keys/radius, tuning acknowledgement, narrow telemetry |
| `SwerveSubsystem.java` | Reset serialization, snapshot validation, measurement reuse, diagnostics and whole-drive inhibit |
| `PhoenixOdometryThread.java` | Atomic bounded sample append, reset generation, shutdown |
| `GyroIO.java`, `GyroIOPigeon2.java`, `GyroIONavX.java` | Reset queue clearing and lifecycle; Pigeon refresh after explicit yaw reset |
| `ModuleIOSim.java`, `GyroIOSim.java` | Shared acquisition timestamps; disabled-only simulation tuning |
| `SwerveCalibration.java` | Explicit calibration revision invalidation |
| `util/SparkUtil.java` | Existing timestamp helper accepts common acquisition base |
| `util/LoggedTunableNumber.java`, `TelemetryCadence.java` | Late activation safety, complete checks, clock-driven rate gate |
| `src/test/java/**/swerve/*Test.java`, `src/test/java/**/util/*Test.java`, `src/test/resources/swerve-control-trace.csv` | Regression, concurrency, logging and numeric evidence |
| This report | Scope, evidence, limitations, human validation and rollback |

## Human-operated hardware validation

**BLOCKED prerequisites:** a qualified team member must establish the intended physical
profile and CAN wiring under the normal hardware/configuration approval process:

1. `GlobalConstants.ROBOT` is `SIMBOT`, including when execution MODE is REAL.
   Its Kraken drive/turn torque gains are zero. Transaction success does not establish
   that these are appropriate real-robot gains. No profile/gain correction is made here.
2. Motors and Pigeon use `DriveTrain`; CANcoders are constructed on the default bus;
   odometry CAN-FD detection also checks the default bus. Confirm actual wiring and
   intended clock/update behaviour; this task does not guess or change either.
3. Confirm approved firmware, sensor offsets/calibration, interlocks and independent
   exact-revision review before any physical test. No simulated PASS replaces this.

Once prerequisites and normal human authorization are satisfied:

1. A human records exact baseline/candidate SHAs, hardware profile, firmware, calibration,
   battery/DS conditions and receiver settings. Use a known-safe approved baseline,
   not the unresolved SIMBOT/REAL combination merely because it builds.
2. Human operators follow the team's restrained/raised-robot procedure and emergency-stop
   provisions. Validate disabled startup, readiness/errors, zero trim and sensor timestamps
   before permitting motion. Deliberately test configuration failure/enable transitions only
   under that controlled procedure; verify every drivetrain output remains inhibited.
3. For equivalent approved inputs, check wheel/gyro direction, gains/current limits,
   command response, reset/recovery, gyro fallback and SysId logs. Verify no sensor-rate
   reduction, lost required inputs, mispaired samples or unexplained queue drops.
4. Run comparable baseline and candidate workloads with identical instrumentation. Existing
   AdvantageKit fields are common to both revisions; new substage fields attribute candidate
   work only unless a separately reviewed identical baseline instrumentation patch is used.
   Separate startup from steady state. Allow at least 30 seconds warm-up, then collect at
   least five equal-duration runs per revision; alternate order and document deviations.
5. Retain WPILOG and WPILib/DS logs. For each workload/reporting interval, calculate count,
   median, p95, p99, maximum and count/fraction of `FullCycleMS > 20`. Document the quantile
   convention. Report GC time/counts, receiver backlog, sensor sample counts/drop counts,
   configuration revisions/errors, and comparable control results alongside elapsed times.
   Preserve per-run summaries; do not hide a bad run by pooling it into a median.
6. Mark hardware PASS only for observed acceptance criteria. A loop regression, missing
   sensor/fault/SysId data, wrong physical response or unexplained loss keeps readiness blocked.
   No improvement percentage is valid until those measured runs exist.

## Rollback

Before publication, preserve/discard only this isolated task worktree through normal
Git review; never reset/clean the original workspace. After publication, create a task
branch from the current integration branch and revert only this task's actual commit(s),
then run the same build/tests/format and governance gates. Revert configuration/readiness,
lock/reset, measurement and telemetry changes together, rather than restoring periodic
writes independently. Do not use an audit SHA as a destructive reset target.

A human alone decides whether to deploy the approved rollback. This pass does not
change stored hardware constants or automatically revert existing calibration preferences.

## Source references

- [AdvantageKit 26.0.2 Logger](https://github.com/Mechanical-Advantage/AdvantageKit/blob/00b1b623cdb9cf291c3554263240f4a4ce61ecc7/akit/src/main/java/org/littletonrobotics/junction/Logger.java): exact metric names, main-thread logging and receiver queue.
- [AdvantageKit 26.0.2 WPILOGWriter](https://github.com/Mechanical-Advantage/AdvantageKit/blob/00b1b623cdb9cf291c3554263240f4a4ce61ecc7/akit/src/main/java/org/littletonrobotics/junction/wpilog/WPILOGWriter.java): changed-value disk recording.
- [CTRE configuration guidance](https://v6.docs.ctr-electronics.com/en/stable/docs/migration/migration-guide/configuration-guide.html): configuration calls block. Phoenix 26.3.0 source was inspected for configurator locking/status behaviour.
- WPILib 2026.2.1 cached `SwerveDriveKinematics.java` source: inverse-kinematics calls mutate remembered headings; their ordering is preserved.
