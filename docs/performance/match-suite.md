# Match-performance suite

Use `tools/performance/match_suite.py` to collect repeatable desktop match workloads
and analyze human-collected robot logs. Read the separate results report,
[roborio-match-performance.md](roborio-match-performance.md), before interpreting a
run as qualification. A completed process is not necessarily a valid workload or a
passing performance result.

## Modes and current robot coverage

| Mode | What executes | What the result establishes |
| --- | --- | --- |
| `DESKTOP_MATCH_SIM`, `paced` | Real Robot, command scheduler, driver bindings, four-module drivetrain, MapleSim IO/physics, estimator, logging, HTTP and NT; separate client processes | Desktop execution and scheduling under the recorded offered and achieved load |
| `DESKTOP_MATCH_SIM`, `stepped` | The real application with simulated DS/HID, deterministic ideal sensor fixtures and stepped HAL timing | Functional scenario evidence; performance qualification is `NOT MEASURED` |
| `ROBORIO_OBSERVE` | Startup-selected observer around the approved normal robot program; humans deploy, enable and drive | Actual on-device evidence only for the recorded hardware, artifact and session |
| `ROBORIO_COMPUTE_ONLY` | Not provided | `UNSUPPORTED`: safe isolation from device construction and native services is not established |
| Full-match REPLAY | Not provided | Existing empty drivetrain replay IO does not restore recorded inputs; importing a log is analysis, not replay |

The current composition has swerve, dashboard services and a Vision subsystem with
zero camera IO instances. It has no active shooter, intake or camera pipeline to
benchmark. No deployed autonomous path is available. The autonomous phase uses a
labelled, in-memory desktop PathPlanner fixture through the existing library. It is
excluded from the deployed JAR and does not install a competition autonomous routine.

Normal startup still constructs `Robot` unless `frc.performance.observe=true` is set.
Desktop runners reside in the separate `performance` source set; production gains,
profiles, CAN assignments, 20 ms loop period, 250 Hz odometry target and logging
defaults are unchanged. Primary desktop scenarios explicitly select COMP through the
existing runtime mode mechanism; `HARD_DEBUG` selects DEBUG separately.

## Prepare the host

Use Java 17 and the project's Gradle 8.11 installation. This checkout has no tracked
Gradle wrapper. Set `JAVA_HOME` to an existing Java 17 installation and `GRADLE` to
an existing Gradle 8.11 executable; do not substitute the shell's Java version.
Commands below run from the repository root. Machine-specific paths are deliberately
not supplied.

```sh
"$JAVA_HOME/bin/java" -version
"$GRADLE" --version
"$GRADLE" build spotlessCheck performanceClasspath --no-daemon --console=plain
python3 -m venv build/performance/venv
build/performance/venv/bin/python -m pip install -r tools/performance/requirements-match.txt
build/performance/venv/bin/python tools/performance/match_suite.py preflight
```

`requirements-match.txt` pins host-only XlsxWriter 3.2.9. It adds no robot dependency.
An available `psutil` installation supplies additional host process CPU/RSS/handle
samples; otherwise these fields remain unavailable. The JVM still supplies supported
resource counters. Preflight reports capabilities rather than silently installing
software or connecting to a target. It does not establish hardware readiness.

The build writes `build/performance/classpath.txt` and extracts desktop native
libraries. `--java /absolute/path/to/java` and `--classpath ...` override automatic
selection when needed. Preserve the generated classpath from the exact candidate
build. Do not reuse it after switching artifacts without rebuilding.

## Run a scenario

These are command templates using the prepared host. Long performance runs and
hardware operations must be distinguished from parser/help verification; the results
report lists the commands actually executed for this implementation.

```sh
build/performance/venv/bin/python tools/performance/match_suite.py smoke --timing stepped --output build/performance/stepped
build/performance/venv/bin/python tools/performance/match_suite.py smoke --timing paced --output build/performance/smoke
build/performance/venv/bin/python tools/performance/match_suite.py run --profile NORMAL --seed 1884 --repetitions 5 --output build/performance/normal
build/performance/venv/bin/python tools/performance/match_suite.py suite --repetitions 5 --output build/performance/full
build/performance/venv/bin/python tools/performance/match_suite.py run --profile HARD_DEBUG --output build/performance/debug
build/performance/venv/bin/python tools/performance/match_suite.py overload --output build/performance/overload
build/performance/venv/bin/python tools/performance/match_suite.py soak --profile NORMAL --cycles 2 --output build/performance/soak
```

Each repetition gets a fresh JVM and owned temporary runtime state, including NT
persistence and copied deploy assets. A soak repeats phases in one JVM to reveal
sustained growth. Ctrl-C aborts owned processes, preserves partial evidence and marks
the run accordingly. Do not delete existing logs to make a storage check pass.

The full suite covers IDLE, IDLE_DASHBOARD, EASY, NORMAL and HARD, five repetitions
by default. DEBUG, overload and soak are separate runs. A smaller repetition count
is recorded and must be disclosed. `--minimal` omits detailed selected-thread probes;
it retains loop qualification and workload evidence. A minimal/detailed comparison
measures the incremental detailed instrumentation cost, not all observer overhead.
`--observation-off` disables the added runtime observation publications and JVM probes,
while retaining identical outer-loop timing and workload witnesses on both sides. The
`overhead` subcommand alternates off/on order and keeps each paired result. This measures
the incremental runtime observation cost; shared witness/profiling cost remains in both.
It does not establish zero-instrumentation absolute overhead.

```sh
build/performance/venv/bin/python tools/performance/match_suite.py overhead --profile NORMAL --repetitions 3 --timeline tools/performance/overhead-timeline.json --output build/performance/overhead
build/performance/venv/bin/python tools/performance/match_suite.py smoke --timing stepped --fault blocked_teleop --output build/performance/fault
```

Stepped mode advances HAL time once per application cycle and uses ideal velocity/angle
following with five aligned samples per 20 ms cycle. Its voltage field is a feedforward
request proxy, not measured terminal voltage. Readiness belongs to fixture IO. It models
no motor firmware, current draw, terrain or PID plant dynamics. Paced mode retains the
existing MapleSim model, whose private noise generator has no public seed control.
The scenario seed controls inputs/timeline in both modes; exact repeatability is checked
with the deterministic fixture, not claimed for MapleSim physics.

### Engineering timeline

| Phase name | Full moving run | Smoke |
| --- | ---: | ---: |
| `warmup` | 30 s | 2 s |
| `disabled_before_auto` | 5 s | 1 s |
| `auto_fixture` | 15 s | 3 s |
| `disabled_transition` | 1 s | 1 s |
| `teleop` | 135 s | 6 s |
| `disabled_recovery` | 10 s | 2 s |

Startup/readiness precede this timeline and are recorded separately. The full moving
run is 196 seconds plus startup; these are engineering phases, not official 2027
rules. IDLE presets use 30 seconds warm-up, 60 seconds disabled and 10 seconds recovery.
Warm-up distributions remain visible and are excluded from the steady-state aggregate.

`--timeline /path/to/timeline.json` accepts an array of objects with `name` from the
table and positive finite `durationSeconds`. It permits at most 64 phase objects and
3,600 seconds total per run. Shortening a timeline changes the scenario hash and does
not qualify as a full match. The suite has a 7,200-second schedule/wall-time cap.

### Offered load and bounds

| Profile | Clients | HTTP requests/s | Synthetic NT publish calls/s | HTTP body budget |
| --- | ---: | ---: | ---: | ---: |
| IDLE | 0 | 0 | 0 | None |
| IDLE_DASHBOARD | 1 | 0.2 | 0 | 0.5 MiB/s |
| EASY | 1 | 2 | 100 | 0.5 MiB/s |
| NORMAL | 2 | 5 | 500 | 1 MiB/s |
| HARD / HARD_DEBUG | 4 | 20 | 2,000 | 4 MiB/s |
| OVERLOAD_RECOVERY | Up to 8 | 20 → 40 → 80 → 1 (recovery probes) | 2,000 → 5,000 → 10,000 → 0 | 8 MiB/s |

These are artificial desktop targets, not approved physical-network limits. The
20-second overload steps begin during the teleop workload; removing excess traffic
allows queue and freshness recovery to be evaluated. Stepped correctness runs disable
paced external load, since accelerated HAL time cannot define a fair traffic rate.

HTTP uses persistent connections, a 64-item pending queue, at most eight clients,
a two-second request deadline, no application retries, at most 1 MiB per ordinary
response and an explicit body-byte budget. Slow/disconnected mode is loopback-only.
The inherited server has four ordinary-priority workers, eight pending executor
items, backlog 16, and 16 KiB per active body transfer (64 KiB total transfer arrays).
Executor rejection can close a connection before an exchange exists; it does not
promise a 503 response. The pinned JDK exposes no per-server slow-client timeout.
These limits do not cap JVM/framework memory, thread stacks or socket buffers.

NT uses 32 synthetic topics, 16-byte sequence/value payloads, subscriber storage 64,
20 ms publication/subscription periods and explicit `sendAll`. Its timing ring has
8,192 slots. Offered publication calls, delivered subscriber values, sequence gaps
and same-process-clock return latency are separate measurements. Payload bytes are
not wire bytes or packet counts. Synthetic topics live under a run-specific benchmark
namespace; existing robot telemetry subscriptions are read-only. Coalescing or an
underpowered generator must invalidate offered-load claims.

The observer's optional desktop queue has 4,096 records and a drop counter. Resource
probes run at 1 Hz; detailed Java-thread discovery scans at most 512 identities and
retains at most 64 selected entries per sample. Storage limits are 128 MiB per run,
2 GiB per suite and a 1 GiB free-space reserve, checked during collection. An exceeded
budget aborts capture with partial evidence. These are application bounds, not a
total process-memory guarantee.

### Routes and freshness

The primary asset bundle is `/`, `/index.css`, `/index.js`, `/NT4.js`, `/msgpack.js`
and `/field-2026.png`. Cold, conditional warm and forced requests remain distinct.
The server preserves unversioned static URLs with `no-cache` and weak validators;
unchanged conditional requests return bodyless 304. Runtime JSON and planner manifests
retain `no-store`. `/planner-autos/index.json` is the existing empty manifest contract.
The frontend references additional unimplemented `/api/...` routes; their baseline
404s are not counted as successful dashboard activity or silently implemented here.

## Read the results

Each run writes a manifest, summary, raw CSVs, original log references, offline HTML
and XLSX workbook. The suite also preserves each run and produces an aggregate report.

| Artifact | Purpose |
| --- | --- |
| `manifest.json` | Git and dirty-diff hashes, artifact identity, platform, seed, scenario, load and completeness metadata |
| `run-summary.json` | Independent validity, completeness, correctness, performance and hardware statuses |
| `loop-samples.csv` | Original/effective release, starts/ends, elapsed duration, interval and cycle identity |
| `subsystem-timings.csv` | Named scopes and units; nested scopes remain inclusive |
| `resource-samples.csv` | Supported JVM resource counters; additional host/thread CSVs retain their own producers |
| `http-nt-results.csv` | Offered, achieved and failed traffic evidence |
| `failures-and-events.csv` | Phase/failure/abort evidence |
| `match-performance.html` | Offline visual report with accessible raw evidence references |
| `match-performance.xlsx` | Summary, metadata, phases, timings, CPU/memory, API/NT, failures and comparison tabs |

```sh
build/performance/venv/bin/python tools/performance/match_suite.py report /absolute/path/to/run
build/performance/venv/bin/python tools/performance/match_suite.py compare /absolute/path/to/baseline-run /absolute/path/to/candidate-run
```

Use real output directories in these templates. Comparison rejects mismatched platform,
pacing, profile, seed/scenario, logging, instrumentation, load or loop period. Record
artifact differences and alternate baseline/candidate order where possible. Never compare
a desktop baseline with a roboRIO candidate as an optimization gain.

Elapsed profiling uses `System.nanoTime()`. HAL scheduling timestamps use microseconds
and remain separate from replay/control timestamps. Execution is the outer observed
cycle, including logger before/after work, `loopFunc`, simulation physics and GC probes.
Desktop fixture staging and output-drainer work are outside that measured interval.
A scheduling deadline is **original release + configured period**, captured before the
pinned scheduler rebases an overdue release. A short loop that starts late can miss it.
Headroom is deadline minus completion; execution overruns and deadline misses are
reported separately, with skipped releases and consecutive miss streaks.

AdvantageKit 26.0.2 fields `LoggedRobot/FullCycleMS`, `UserCodeMS`, `LogPeriodicMS` and
`GCTimeMS` use milliseconds; `GCCounts` and `Logger/QueuedCycles` are counts. Its full-cycle
field omits GC sampling and the clone/enqueue tail. The observer does not relabel that
field as complete execution. Previous completed outer-loop data carries its original
cycle/phase identifier in the next log table; the importer joins identities rather than
assuming every field in a table describes the same cycle.

Scheduler includes subsystem/command work. Swerve includes its odometry and lock scopes;
module acquisition is nested inside lock hold. Do not add child times to their parents
or background elapsed work to main-loop elapsed work. Simulation cost is reported
separately but is not subtracted to manufacture a roboRIO estimate. Thread CPU mappings
cover recognized Java threads, not every native service or individual source file.

Quantiles use nearest rank `ceil(p × n)`, with the minimum for p=0. Mean, median, p95,
p99, maximum, missing count and headroom are retained. A maximum is an observation,
not a proven worst-case bound. Do not average run p99s and call the result pooled p99.
One-core-equivalent CPU percent is `100 × CPU-time delta / wall-time delta`; whole-machine
process percent additionally divides by measured CPU capacity. Heap, RSS, GC time,
request latency and loop-budget use are different metrics. Unsupported values remain
unavailable, not zero. A complete-but-invalid workload cannot receive performance PASS.

## Human-operated roboRIO observation

**No hardware procedure below was executed by this suite implementation.** This path
collects evidence; it does not authorize deploying or actuating a robot.

1. Obtain normal exact-artifact review and hardware authorization. Resolve the inherited
   SIMBOT/REAL profile question: the selected SIMBOT Kraken torque gains are zero even
   in REAL mode. Verify motors/Pigeon on `DriveTrain`, CANcoders on the default bus and
   default-bus CAN-FD detection against actual wiring. Do not guess or change these to
   make a test pass. Verify firmware, calibration, interlocks and emergency-stop access.
2. Record actual roboRIO model, CPU capacity, image/runtime, JVM, FPGA revision, device
   firmware, CAN topology, logging destination, artifact SHA-256 and source identity.
   FPGA/serial readings do not identify every motor or sensor firmware version. Unknown
   fields remain null and prevent unsupported identity claims.
3. A human deploys the approved program and adds the following startup JVM properties
   through the team's approved deployment procedure: `-Dfrc.performance.observe=true`,
   `-Dfrc.performance.seconds=300`, and a run-specific `-Dfrc.performance.runId=...`.
   Supply the independently calculated artifact identity with
   `-Dfrc.performance.artifactSha256=...`. `-Dfrc.performance.detailed=true` enables the
   separate diagnostic variant. These are property names verified in source, not a
   tested deployment command. Capture is limited to 600 seconds on hardware.
4. The human performs representative disabled, autonomous and teleop phases with the
   Driver Station. No suite tool supplies joystick values or enables hardware. Keep
   the approved current limits, readiness gates and emergency stop active. Capture
   expiry stops added measurements, not the robot's control loop or normal logging.
5. Start with no external load. For any off-host read-only HTTP/NT experiment, separately
   approve the exact numeric target, clients, rate, byte budget and duration. Run the
   generator on the operator computer on a controlled private network, never a field
   or shared event network. Do not run overload or fault injection on hardware from
   the desktop defaults. The human monitors DS warnings, loop overruns, brownouts,
   communications, sample age and available disk. Agree abort thresholds beforehand;
   immediately stop load for any exceeded threshold, unexpected motion/fault, stale
   control data or operator request. The human remains responsible for disabling.
6. After the session, a human copies the original WPILOG and relevant DS logs to the
   host without deleting source logs. Preserve original names/hashes and phase notes.
   Save an identity/evidence JSON beside the copy. Importing is local file analysis:

```sh
build/performance/venv/bin/python tools/performance/match_suite.py import-roborio /absolute/path/to/original.wpilog --metadata /absolute/path/to/identity.json --output /absolute/path/to/new-import
```

The identity file is a JSON object. This is a **placeholder schema, not measured data**;
replace strings and nulls only with supported session evidence. Do not set verification
booleans merely to obtain PASS. Extra human metadata is preserved in the manifest.

```json
{
  "run_id": "REPLACE_WITH_SESSION_ID",
  "platform": "ROBORIO_OBSERVE",
  "profile": "HUMAN_SESSION",
  "git_sha": "REPLACE_WITH_DEPLOYED_SOURCE_SHA",
  "artifact_hash": "REPLACE_WITH_DEPLOYED_ARTIFACT_SHA256",
  "hardware_identity_verified": false,
  "roborio_model": null,
  "cpu_capacity": null,
  "firmware": null,
  "logging_destination": null,
  "phase_timeline": [],
  "phase_evidence": [],
  "workload_evidence": {
    "phases_reached": null,
    "commands_executed": null,
    "sensor_samples": null,
    "odometry_samples": null,
    "readiness": null,
    "drive_inputs": null,
    "output_changes": null,
    "pose_evolved": null
  }
}
```

The importer checks WPILOG framing, capture completion, record counts and dropped-record
indicators. It preserves partial/truncated evidence and its limitations. Legacy logs
without observer fields or human workload evidence cannot establish full-match PASS.
The import does not automatically associate separately collected network traffic or
infer firmware/hardware approval from a valid file.

The `load` subcommand delegates the finite HTTP generator. Use its help for explicit
limits. Off-host use requires both exact `--allow-host` and `--acknowledge-offhost`;
this acknowledgement must reflect separately approved human limits. It does not grant
approval by itself. Redirects are not followed. The Java NT client accepts analogous
explicit target controls and rejects off-host synthetic publication; only rate zero
subscriptions are permitted. No robot discovery, team-address probing, gain/configuration
writes, auto-selection changes, log deletion, resets or SysId traffic are provided.

## Rollback

Remove `frc.performance.observe` from the next human-approved startup to restore the
normal entrypoint. Retain evidence from the observed run. Revert only this suite's
changes on a task branch through the normal review process; retain prior configuration,
odometry and HTTP optimizations. Rebuild the artifact and desktop classpath. Do not
reset to a historical audit reference, delete logs, change hardware settings or deploy
a rollback automatically.

## Clock correlation and report interpretation

The local Java/Python overlap calculation is enabled only for the verified macOS arm64,
Temurin 17.0.18+8 and CPython 3.11.9 combination recorded in the manifest. Both read
`mach_absolute_time()` with the same kernel timebase: [OpenJDK source](https://github.com/openjdk/jdk17u/blob/jdk-17.0.18%2B8/src/hotspot/os/bsd/os_bsd.cpp#L784-L819)
and [CPython source](https://github.com/python/cpython/blob/v3.11.9/Python/pytime.c#L1087-L1109).
This establishes same-host millisecond-scale correlation, not causation or cross-host
clock alignment. Other runtime combinations remain UNVERIFIED until checked. Same-JVM
NT round-trip timing does not depend on this cross-process check.

Performance status describes warmed steady state. Startup/warm-up misses remain in the
all-cycle and phase tables. Resource sampling runs on the next eligible robot loop at
least one second after its preceding sampling loop; cadence drift is reported separately
from missing records. Raw loop/subsystem/resource samples remain adjacent to the reports.

CLI exit codes: 0 means the selected check completed successfully; 1 means a completed
qualification failed its functional/performance criteria; 2 means invalid/incomplete/
aborted evidence or invalid arguments. Intentional desktop fault runs retain their failed
workload checks to prove detection; they are not successful match qualifications.

Read-only NT load is available through the same entrypoint:

```sh
build/performance/venv/bin/python tools/performance/match_suite.py load --nt --java "$JAVA_HOME/bin/java" -- --host 127.0.0.1 --port 5810 --output build/performance/nt.csv --duration 10 --updates-per-second 0 --clients 1 --run-id local-readonly
```

For an explicitly approved off-host target, pass its literal IP and the matching
`--allow-host` plus `--acknowledge-offhost`. Synthetic publishing remains forbidden
there. Actual dashboard contracts include inherited topics/routes without producers;
the benchmark subscribes to eight verified production telemetry keys at 50 ms with
coalescing enabled, and records each key's delivery. Synthetic topics are separately
configured at 20 ms/sendAll with sequence accounting. These are engineering workloads,
not a claim that every dashboard feature is implemented.
