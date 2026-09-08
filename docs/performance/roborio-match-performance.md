# Season2027 match-performance evidence

## Scope and status

This report accompanies the repeatable suite documented in [match-suite.md](match-suite.md).
It separates functional tests, desktop execution measurements and human-operated
hardware validation. **Actual roboRIO timing, overload capacity and loop-time improvement
remain NOT MEASURED. No robot deployment or physical actuation was performed.**

- Development base: `b999cc80e7fad1a1aa0d24c8ba682146952d3348`.
- Task branch: `task/match-performance-suite`, in an isolated checkout.
- Reference context: `1bbee62e56870bf55e7564cdf552e15cc8a381f7`; no reset to this reference.
- Pass 1 is present in the base through `core-mechanisms-lead` integration. Its checked
  startup gains, serialized configuration worker, readiness gates, odometry snapshots,
  measurement reuse and telemetry policy remain intact.
- Pass 2 is present through `b999cc8`; PR #24 was the pending integration dependency
  when this suite began. Its bounded HTTP streaming/workers and validators remain intact.
  The suite's eventual promotion must follow the current branch state and Harness policy.
- Original checkout `task/ci-build-repair` and its unrelated untracked files were preserved.
- Risk: **HIGH**. The optional observer owns loop scheduling/logger integration, and
  desktop DS injection must remain isolated from real hardware. Plan agreement does
  not replace validation, exact-diff learning verification, independent review, Safety
  Code Owner approval or hardware authorization.

## Design and measurement boundaries

Default `Main` still constructs the existing `Robot`. The startup property
`frc.performance.observe=true` selects `ObservedRobot`, using pinned AdvantageKit
26.0.2 public hooks and WPILib's protected application loop. It preserves the scheduling
rebase behavior while recording the original release before rebase. It records complete
outer execution, including logger tail and GC probes that the existing AKit full-cycle
field does not fully include. This is a maintained, version-specific integration;
upgrading AKit/WPILib requires reviewing the scheduling and logger-order tests.

Loop elapsed time uses the monotonic execution clock. HAL scheduling microseconds are
kept separate from simulation/replay/control timestamps. A miss means completion after
original release plus the actual configured period; short execution can still miss if
it starts late. Stepped simulation proves behavior and reports performance qualification
as NOT MEASURED. Physics time remains included and is also reported separately; it is
never subtracted and labelled roboRIO execution.

Previous completed-loop measurements are published with their cycle identity in the
next table. Current-cycle subsystem data retains its own identity. Import preserves
that distinction, validates framing/count/completion evidence and marks truncated or
missing data incomplete. It neither replays actuator commands nor repairs baseline
replay restoration.

Existing `Robot/Performance`, `Swerve/Performance` and AKit keys are reused. The added
swerve observation seam records requests/applied requests, sample acquisition/consumption,
readiness and setpoint/control boundaries without changing control algorithms. Main-loop
metrics are inclusive; nested subsystem scopes and background worker times are not
summed as independent CPU costs. A loop-quantized, non-catch-up 1 Hz resource probe distinguishes supported process
CPU, heap, GC and selected Java-thread counters. Native-only threads, unsupported JVM
metrics, true allocation rate and unmeasured whole-system/network/CAN metrics remain
unavailable rather than zero.

The desktop source set drives the real scheduler/bindings and four-module swerve. Paced
runs retain MapleSim physics; stepped runs use a deterministic ideal sensor fixture. DS updates use the pinned simulation APIs. An in-memory PathPlanner fixture
exercises the available autonomous library because there is no deployed routine to
qualify. Vision has zero active camera IOs; no hypothetical mechanisms or camera
pipeline are included. Each ordinary repetition starts a fresh JVM; a soak deliberately
retains one application across finite repeated phases.

## Workloads and resource limits

Full moving timeline: startup separately, 30 s warm-up, 5 s disabled, 15 s autonomous
fixture, 1 s disabled transition, 135 s teleop and 10 s disabled recovery. This is an
engineering timeline, not a statement of official 2027 game rules. Primary profiles
use COMP; HARD_DEBUG is a distinct diagnostic workload. All configured software limits,
20 ms loop period and 250 Hz odometry target are retained.

| Profile | Clients | Offered HTTP/s | Offered synthetic NT updates/s | Repetition policy |
| --- | ---: | ---: | ---: | --- |
| IDLE | 0 | 0 | 0 | Five in full suite |
| IDLE_DASHBOARD | 1 | 0.2 | 0 | Five in full suite |
| EASY | 1 | 2 | 100 | Five in full suite |
| NORMAL | 2 | 5 | 500 | Five in full suite |
| HARD | 4 | 20 | 2,000 | Five in full suite |
| HARD_DEBUG | 4 | 20 | 2,000 | Separate; disclose actual count |
| OVERLOAD_RECOVERY | Up to 8 | 20 / 40 / 80 / 1 (recovery) | 2,000 / 5,000 / 10,000 / 0 | Finite separate ladder |
| SOAK | Selected profile | Selected profile | Selected profile | Two match cycles by default |

HTTP clients use a finite 64-item work queue, at most eight concurrent clients,
two-second deadlines, no application retries and explicit 0.5/1/4/8 MiB/s body budgets.
The inherited server remains four workers/eight pending tasks/backlog 16, with at most
four 16 KiB transfer arrays. NT uses 32 run-specific topics, 16-byte payloads, 64-entry
subscriber storage and an 8,192-slot sequence/timing ring. Offered publication calls,
subscriber delivery, coalescing, payload bytes and actual wire traffic are distinct.

Per-run storage is limited to 128 MiB, suite storage to 2 GiB with 1 GiB free reserve.
The optional desktop loop queue holds 4,096 records and counts drops. Resource sampling
is 1 Hz. Detailed selected-thread observation bounds scanning/retention. These bounds
cover application resources, not total process memory, socket buffers or every JDK
allocation. Observer capture on real hardware defaults to 300 s and is capped at 600 s;
expiry ends added measurement, not normal control/logging.

## HTTP and logging coverage

The actual six-resource main bundle totals 419,437 body bytes at the inherited base.
It includes root HTML, CSS, JavaScript modules and the field PNG. The generator separates
cold delivery, conditional revalidation and forced requests. Static resources preserve
`no-cache`/weak ETag semantics; runtime JSON and the existing planner manifest preserve
`no-store`. A bodyless 304 proves no response body retransmission, not zero I/O or CPU.
Existing missing `/api/...` routes remain missing and are not accepted as successful
load. No frontend, NT control topics or server cache policy is redesigned here.

WPILOGWriter, NT4Publisher, DataLogManager NT capture, Driver Station logging and URCL
remain enabled according to existing production behavior. Added observation is opt-in.
Primary desktop logging uses the runtime COMP profile, with DEBUG isolated. Required
fault, sensor, SysId and runtime logging defaults are not reduced to obtain a benchmark
PASS. Startup and warm-up are reported separately; all steady-state spikes remain.

## Changed areas

| Area | Purpose |
| --- | --- |
| `Main.java`, `performance/ObservedRobot.java` | Startup-only observer and pinned outer-cycle/logger boundary |
| `Robot.java`, `RobotContainer.java` | Narrow desktop composition/observation access and owned log/lifecycle seams |
| `performance/LoopSample*`, `CaptureWindow`, `ResourceProbe` | Bounded capture, deadline semantics and supported resource counters |
| `subsystems/swerve/SwerveSubsystem.java` | Gated validity counters and control attribution; no algorithm change |
| `src/performance/java/**` | Desktop scenario/DS/HID runner and separate NT generator, excluded from robot JAR |
| `build.gradle` | Protected-path approved source-set/classpath/native-test plumbing; no dependency upgrade |
| `src/test/java/**/performance/**` | Deterministic harness and isolation regression tests |
| `tools/performance/match_*`, related tests/requirements | Host orchestration, HTTP generation, analysis, WPILOG import and offline reports |
| Existing `OperatorBoardResourceTest` | Correct a pre-existing race: wait for transfer cleanup and enclosing handler failure accounting |
| This report and `match-suite.md` | Evidence, operating instructions, limitations and rollback |

The final reviewed diff determines the exact file list. No gains, physical constants,
CAN assignments, deployed assets, production logging defaults or Harness policy changes
are part of this suite.

## Validation and measured results

Collection environment inspected for local work: macOS 26.5 arm64 on Apple M1, eight reported
logical processors, Temurin 17.0.18, Gradle 8.11 and Python 3.11.9. Resolved robot libraries
are WPILib/GradleRIO 2026.2.1, AdvantageKit 26.0.2, Phoenix 26.3.0, PathPlanner 2026.1.2,
REVLib 2026.0.5, URCL/Studica 2026.0.0, maple-sim 0.3.14 and dyn4j 5.0.2. Each run manifest
records its actual runtime, source/dirty/artifact hashes and workload rather than relying
on this environment description.

The inherited pass-2 base had 94 passing local tests and a passing build-and-format CI
run. This is baseline evidence, not a test result for the suite implementation.

The primary suite completed **25 desktop runs: five per profile**, all with complete
measurement capture. **23 workloads were valid; HARD repetitions 1 and 2 were INVALID**
because the NT generator dropped scheduled publications. HARD therefore has three valid
repetitions, all performance FAIL, and two INVALID repetitions. **No profile qualified
across all five repetitions.** A valid workload with a timing miss is a performance FAIL;
an underdelivered workload remains INVALID regardless of its measured execution time.
These measurements establish desktop behavior on this host, not roboRIO capacity.

Primary artifacts: `/tmp/season2027-match-suite-evidence-20260908/primary-suite/`.
Each completed run retains its manifest, raw samples, original logs and HTML/XLSX reports.
The compact numerical source used for the tables below is
`/tmp/season2027-match-suite-evidence-20260908/primary-numbers.json`.

- Measured main artifact SHA-256:
  `77f1f8f97f0087855bedddbdfb89969b7b7056f81ca0f1d3b822d7db991b1c21`.
- Desktop performance-class hash:
  `0569a7460aa0e43b92a9a59fc9e2cac9c51f795960fbbca211bb07db302a7df9`.
- Actual JVM: Temurin **17.0.18+8**; host Apple M1, macOS 26.5 arm64, eight logical
  processors; CPython 3.11.9. Runtime and dirty-diff identities remain in each manifest.

The tables pool the actual warmed steady-state samples for each profile, using nearest
rank `ceil(p × n)`. They do not average repetition p99 values. **The HARD rows currently
describe all five completed trials, including two INVALID workloads, and must not be
used as a valid-load qualification distribution.** Those trials remain visible rather
than being silently discarded. Startup/warm-up remain in
per-run all-cycle/phase reports and do not enter this qualification table. Counts below
are executed cycles; a separate release accounting column preserves additional expired
slots inferred at scheduler rebase. The schedule is the pinned rebasing schedule, not
an invented fixed global schedule. All timing values are milliseconds.

| Profile | Steady samples | Mean | Median | p95 | p99 | Maximum | Repetitions with no steady deadline miss |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| IDLE | 17,499 | 0.853 | 0.819 | 1.405 | 2.159 | 25.944 | 4/5 |
| IDLE_DASHBOARD | 17,495 | 0.816 | 0.793 | 1.309 | 2.106 | 13.091 | 3/5 |
| EASY | 41,248 | 0.795 | 0.759 | 1.289 | 2.042 | 35.073 | 0/5 |
| NORMAL | 41,250 | 0.792 | 0.768 | 1.258 | 1.951 | 25.315 | 3/5 |
| HARD, all five trials (descriptive only) | 41,241 | 0.833 | 0.788 | 1.417 | 2.034 | 44.181 | 0/3 valid; 2 INVALID |

| Profile | Execution overruns | Executed-cycle deadline misses | Miss fraction | Additional expired slots | Total missed release slots | Longest miss streak | Median headroom | Minimum headroom |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| IDLE | 1 | 1 | 0.0057% | 0 | 1 | 1 | 15.184 | -14.178 |
| IDLE_DASHBOARD | 0 | 5 | 0.0286% | 2 | 7 | 2 | 15.627 | -75.676 |
| EASY | 6 | 10 | 0.0242% | 0 | 10 | 2 | 15.511 | -18.802 |
| NORMAL | 2 | 3 | 0.0073% | 0 | 3 | 1 | 15.809 | -8.735 |
| HARD, all five trials (descriptive only) | 7 | 15 | 0.0364% | 2 | 17 | 2 | 15.822 | -71.047 |

All five pooled datasets have zero missing execution/deadline samples. Median timing
margin was approximately 15–16 ms, but negative minimum headroom demonstrates occasional
misses. IDLE_DASHBOARD had no execution longer than 20 ms yet missed five executed-cycle
deadlines, illustrating why execution duration alone is insufficient. HARD's maximum
execution was 44.181 ms and its minimum scheduled headroom was −71.047 ms; these are
observed samples, not proven worst-case bounds or a causal attribution to HTTP/NT.

### Achieved primary traffic

Counts cover the captured traffic workload for all five runs of each profile, using
each run's recorded traffic window; they are not restricted to the pooled steady-state
loop window above. NT delivered values aggregate all subscribers, so NORMAL has two
and HARD four deliveries per published value when delivery is complete. Read-only
production subscriptions are additional evidence; zero synthetic updates for IDLE_DASHBOARD
does not mean that its telemetry subscriptions received nothing.

| Profile | HTTP completions | HTTP 304s | Synthetic NT publications | Synthetic values delivered to subscribers |
| --- | ---: | ---: | ---: | ---: |
| IDLE | 0 | 0 | 0 | 0 |
| IDLE_DASHBOARD | 95 | 30 | 0 | 0 |
| EASY | 1,940 | 590 | 97,000 | 97,000 |
| NORMAL | 4,850 | 1,440 | 485,000 | 970,000 |
| HARD | 19,400 | 5,744 | 1,939,553 | 7,758,212 |

These are completion/delivery counts, not inferred wire packets, instantaneous throughput
or proportional CPU savings. Per-run traffic summaries retain actual offered/achieved
rates, scheduling delay, response latency and generator resource evidence. HARD delivered
all 1,939,553 measured publications to four subscribers (7,758,212 values). However, HARD repetition 1 scheduled 388,000 publications and published 387,733
(267 locally dropped); repetition 2 published 387,820 of 388,000 (180 locally dropped).
All actually published values reached all four subscribers with zero undelivered values.
That delivery success does not recover the **447 missed offered publications** or make
those two workloads valid. Their timing and traffic remain retained as descriptive
evidence, separate from the three valid HARD runs. Use each manifest's scheduled, started
and published counters, not nominal-rate arithmetic, to assess offered-load achievement.

### Execution record and interruptions

The primary invocation selected `suite --repetitions 5`. A storage preflight aborted the
initial NORMAL repetition 5 before collection; its ABORTED artifact is retained separately
and is not included in the 25 completed runs. After freeing approximately 400 MB of
reproducible build caches while preserving the JAR, tests and logs, the remaining runs
used `suite --profiles NORMAL,HARD --repetitions 1 --first-repetition 5`. The resumed
runs preserve repetition identity. No timing outlier or failed steady-state sample was
dropped to obtain a PASS.

A failed report-regeneration attempt also exhausted its available storage budget.
Only verified redundant derived CSVs from that regeneration were removed; original
measurement data and logs were preserved. Final completeness checking accounts for the
actual resource sampler cadence (the next eligible loop at least one second later),
instead of incorrectly treating cadence drift as lost records. These host/reporting
corrections do not change the measured Java artifact.

| Validation item | Result | Evidence / limitation |
| --- | --- | --- |
| Current Java regression tests | PASS | 118 tests reported by final integration validation; final command evidence retained by integrator |
| Current Python harness tests | PASS | 56 deterministic host tests; `final-host-validation2.log` |
| Repeated deterministic production trace | PASS | Identical 400-cycle / 2,000-sample traces reported across ten plus two positive fixture runs; fixture correctness is separate from paced qualification |
| Primary paced suite workload validity | 23 PASS / 2 INVALID | HARD repetitions 1 and 2 had 267 and 180 locally dropped NT publications; all 25 runs retained |
| Primary paced measurement completeness | PASS | All 25 complete after cadence-aware resource validation; original samples retained |
| Primary paced performance | No profile qualified across all repetitions | HARD has 3 valid FAIL / 2 INVALID; the other 20 workloads are valid with individual timing results above |
| Interrupted storage attempt | ABORTED, retained | Initial NORMAL repetition 5; replaced by a separately identified completed repetition |
| Final Java build/format | PASS | Integration validation completed with 116 passing Java tests |
| Final Python rerun | Pending integrator evidence | Earlier 49-test PASS predates additional reporting tests |
| Observer-off/shared-witness versus detailed | PASS comparison validity | Three matched pairs; median paired median change +0.031250 ms |
| DEBUG / short soak | Valid and complete; performance FAIL | One shortened run each; one steady deadline miss in each |
| Overload / recovery | Load INVALID; recovery PASS | NT generator dropped 462 scheduled publications; containment evidence remains separately useful |
| Two full-length matches in one JVM | NOT RUN | The collected short soak is two 81-second timelines |
| Final report rendering / XLSX validation | PASS for HTML and workbook structure/numbers | Chromium 1440 px layout checked; all eight workbook tabs and chart caches tested. Excel/LibreOffice visual rendering NOT RUN |
| Human-operated roboRIO session | NOT RUN / NOT MEASURED | Hardware prerequisites unresolved |
| Full-match replay / compute-only roboRIO | UNSUPPORTED | Input restoration / isolation limitations |

### Auxiliary runs and observer cost

Auxiliary measurements use the same compiled main artifact and execution platform as
the primary suite. Their shortened timelines, repetitions and failure expectations remain
separate. The source summary is
`/tmp/season2027-match-suite-evidence-20260908/auxiliary-numbers.json`.

| Experiment | Recorded timeline | All loop samples | Steady samples | Steady median / p95 / p99 / maximum (ms) | Steady deadline misses | Validity / completeness / functional / performance |
| --- | --- | ---: | ---: | --- | ---: | --- |
| OVERLOAD_RECOVERY | 196 s, one run | 9,798 | 8,250 | 0.830 / 1.357 / 1.932 / 23.006 | 1 | INVALID / PASS / PASS / INVALID |
| HARD_DEBUG | 81 s, one diagnostic run | 4,048 | 2,498 | 0.831 / 1.325 / 2.064 / 27.172 | 1 | PASS / PASS / PASS / FAIL |
| Short SOAK | 162 s, two 81 s timelines in one JVM | 8,099 | 5,001 | 0.831 / 1.385 / 2.309 / 27.272 | 1 | PASS / PASS / PASS / FAIL |

Auxiliary run IDs and paths are:

- `/tmp/season2027-match-suite-evidence-20260908/overload/20260908T191200-overload_recovery-1884-1`.
- `/tmp/season2027-match-suite-evidence-20260908/debug/20260908T191732-hard_debug-1884-1`.
- `/tmp/season2027-match-suite-evidence-20260908/soak/20260908T192049-normal-1884-1`.

The full overload timeline completed 3,903 of 3,903 HTTP requests, including 1,156
bodyless 304s, with 163,593,470 response-body bytes. The NT generator scheduled 442,000
publications, published 441,538 and locally dropped 462. All 3,532,304 expected subscriber
values for those actually published values were delivered. Successful delivery does not
repair missing offered work: overload workload/performance is **INVALID**, so this run
does not establish the server's saturation threshold at the requested ladder.

Recovery obligations **passed**: the final HTTP executor queue was zero, 56 post-load
HTTP requests succeeded, NT telemetry continued, odometry consumption increased and the
last sensor age was 3.483 ms against the derived 24 ms freshness limit. These observations
establish the specified service/freshness recovery checks. They do not establish memory
recovery, leak freedom or a physical robot's response to overload.

The separate observer-cost experiment completed three matched off/detailed pairs with
comparison validity PASS. The median of the three paired **steady execution median
changes** was **+0.031250 ms**, with pair changes from **−0.004000 to +0.037333 ms**.
This is a summary of paired differences, not a pooled execution quantile. Both variants
retain the same outer timing witness, desktop frame writer and workload checks. The
difference estimates the additional runtime observation/resource-telemetry cost under
this host workload, with ordinary run variability; it is not absolute zero-probe overhead
or a roboRIO optimization result. Raw paired reports remain under
`/tmp/season2027-match-suite-evidence-20260908/overhead/`.

### Auxiliary resource endpoints

The table reports the first and last **available sampled values**, in bytes/counts.
JVM and host probes begin at different stages; host first samples can precede JVM/service
startup. The endpoints are not matched warmed steady states and must not be interpreted
as retained-allocation growth or proof of recovery. No forced GC was used.

| Run | JVM heap used, first → last (bytes) | JVM threads, first → last | Host RSS, first → last (bytes) | Host file descriptors, first → last | Logged receiver queue, first → last |
| --- | --- | --- | --- | --- | --- |
| OVERLOAD_RECOVERY | 27,514,176 → 33,764,664 | 16 → 18 | 16,777,216 → 138,149,888 | 3 → 58 | 0 → 0 |
| HARD_DEBUG | 27,601,552 → 69,080,320 | 16 → 18 | 17,334,272 → 163,053,568 | 4 → 58 | 0 → 0 |
| Short SOAK | 26,640,352 → 49,283,768 | 15 → 18 | 15,532,032 → 165,969,920 | 3 → 58 | 0 → 0 |

The short soak demonstrates cleanup/recovery observations over two shortened cycles in
one JVM. Two full-length match cycles and longer leak qualification were **NOT RUN**.
Increasing RSS or heap endpoints over this interval alone does not distinguish startup,
normal allocation/GC phase and a leak. Consult the full resource traces and repeat a
longer, controlled soak before claiming sustained resource stability.

Final Python test counts, report rendering and workbook validation are recorded after
the final report-tool changes. Preliminary invalid runs are retained as development or
fault evidence and do not replace the measured primary qualification set.

For the **three valid HARD runs alone**, pooled warmed steady state contains 24,748
samples: mean 0.773 ms, median 0.748 ms, p95 1.248 ms, p99 1.822 ms and maximum
33.158 ms, with four deadline misses and a longest consecutive streak of one.
These qualified-input descriptive figures exclude the two generator-limited trials;
those trials and their raw measurements remain separately available above.

## Remaining prerequisites and limitations

- Actual roboRIO loop-time performance, CPU/memory behavior, CAN load and device/firmware
  costs are NOT MEASURED. Desktop physics includes costs absent from production hardware
  and omits hardware/CAN costs; neither difference yields a valid extrapolation.
- Resolve SIMBOT selected in REAL mode and the named `DriveTrain` versus default encoder/
  CAN-FD status bus questions through the normal hardware process. A successfully applied
  configuration is not proof that the physical profile is safe.
- Existing REPLAY has no complete recorded module/gyro input restoration. Legacy log import
  can expose available metrics but cannot establish complete match workload validity.
- Existing GyroIOSim yaw-rate unit conversion remains an inherited limitation.
- There is no active camera pipeline or deployed autonomous routine in the tested composition.
  Fixture autonomy and synthetic NT transport do not establish nonexistent feature coverage.
- JVM monitoring support varies by platform. Selected Java-thread CPU is not exhaustive
  native-service attribution. Sampled RSS/heap is not allocation rate or a guaranteed peak.
- Localhost generators share the desktop machine even though processes are separate. Their
  measured CPU/resource use must be considered when offered load is not achieved.
- The inherited HTTP runtime lacks public per-server request/response timeout controls;
  bounded executor work does not establish a complete connection or process-memory bound.

## Hardware follow-up and rollback

Follow the step-by-step human procedure in [match-suite.md](match-suite.md#human-operated-roborio-observation).
Record approved artifact/hardware identity and phase evidence, capture with the opt-in
observer, manually transfer original logs, then use `import-roborio` on the host. Humans
alone deploy, change DS modes and drive. Separately approve off-host read-only targets
and finite limits; never automatically run desktop saturation or physical control input.
Keep the operator in control and stop load at the pre-agreed abort conditions.

Removing the observer startup property restores the normal entrypoint on the next
human-approved start. For source rollback, revert this suite's reviewed diff on a task
branch, rebuild and follow normal review/deployment controls. Retain earlier optimization
passes and all captured logs. Do not reset to historical references or automatically
deploy/merge a rollback.

## Final validation commands and artifact interpretation

Commands actually run in the isolated task worktree used Temurin 17.0.18 and the
installed Gradle 8.11 binary (the checkout contains no wrapper):

```sh
JAVA_HOME=/Users/jonathanst-georges/Library/Java/JavaVirtualMachines/temurin-17.0.18/Contents/Home /Users/jonathanst-georges/.gradle/wrapper/dists/gradle-8.11-bin/c4te04g51qsyw1bxcb929u7br/gradle-8.11/bin/gradle spotlessApply build performanceClasspath --no-daemon --console=plain
/tmp/season2027-match-suite-host-venv/bin/python -m unittest discover -s tools/performance -p 'test_match*.py'
```

The final build/test/format run passed 118 Java tests. The host tests passed 56.
An inherited web test exposed a cleanup/failure-counter race under concurrent local
builds; its wait now observes both completion signals. One intermediate test edit
applied that wait to the wrong test and failed; it was corrected before the passing
runs. No server runtime behavior changed.

The final-source NORMAL smoke (`release-smoke/20260908T194918-normal-1884-1`) recorded
749 loops, valid/complete/correct behavior, 1.020 ms all-cycle median, 3.821 ms p99,
38.533 ms maximum and three all-cycle deadline misses. Its performance result is FAIL.
The final NT generator change only tightened post-collection success/exit classification
for incomplete delivery. The robot JAR remained unchanged; manifests preserve the
performance-class hash used for every measurement instead of substituting the later hash.

A CLI `import-roborio` round-trip used the actual desktop log
`smoke/20260908T170821-normal-1884-1/logs/akit_26-09-08_18-08-25.wpilog` and preserved
its DESKTOP_MATCH_SIM identity. It recovered 748 loop records with matching timings
and measurement completeness PASS. Its workload remains INVALID because the imported
bundle does not contain the original companion HTTP/NT results. This validates import
mechanics; it is not hardware or full-match replay evidence.

Raw evidence is retained under `/tmp/season2027-match-suite-evidence-20260908/`.
The primary suite's manifest/raw-file index links all per-run CSVs and original logs;
large CSVs are not duplicated into the suite workbook. Reporting resource guards cover
existing, temporary and replacement CSVs. A reporting-space failure is BLOCKED with
nonzero CLI status, preserving the original capture. Regeneration skips unchanged CSVs.

No robot deployment, physical actuation, automated off-host load, merge or independent
PR approval was performed. Internal implementation review is separate from the required
future independent PR review and exact-head human approval.
