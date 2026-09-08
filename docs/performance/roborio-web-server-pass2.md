# Operator-dashboard HTTP delivery: pass 2

## Scope and status

Approved plan revision 1; **MEDIUM risk** (dashboard availability, freshness and shared
roboRIO resources). This report records the validated implementation before publication.
No robot deployment, physical actuation, automated robot discovery or physical-robot
load test was performed. **roboRIO loop-time improvements remain NOT MEASURED.**

- Base/integration target: `core-mechanisms-lead`,
  `f0e9366d3541dedaa3159dc4451354d1580ed025`.
- Pass 1 is present through merged PR #22 (`0bd2b4e`), with successful CI, and was
  promoted to `main` through PR #23 (`1bbee62`). Its source is preserved unchanged.
- The original checkout was the older `task/ci-build-repair` at `3440b0c`; its
  untracked wrapper and NetworkTables files remain untouched. Work used an isolated
  `task/operator-dashboard-http-pass2` branch.
- Frontend assets, Config, Robot/RobotContainer, drivetrain, NetworkTables contracts,
  dependencies, Java/build configuration, CI and Harness policy are unchanged.

## Design and resource limits

Before: every asset request used `Files.readAllBytes`, probed MIME type, sent
`Cache-Control: no-store`, and ran on an unbounded cached executor.

After: four ordinary-priority named `OperatorBoardHttp-*` workers use an
`ArrayBlockingQueue` of eight tasks. Each active body uses one 16 KiB array, with
no file-body open or transfer-buffer allocation for queued requests. Files stream
with a fixed Content-Length; the final chunk is withheld until size and metadata
are rechecked. Empty files use a bodyless 200 with Content-Length zero.

| Resource | Bound / policy |
| --- | --- |
| HTTP workers | 4, Java normal priority; no real-time priority calls |
| Pending executor tasks | 8 |
| Requested TCP listen backlog | 16; separate OS-managed queue |
| Active body channels | At most 4 |
| Transfer arrays | 16 KiB each; at most 64 KiB across four transfers |
| Asset-content cache | None |
| Validator/path-keyed cache | None; one canonical configured-root Path is retained |
| MIME lookup | Fixed five-entry table for existing common extensions; unknown types use the existing probe/fallback |
| Diagnostics | Fixed counters and 16 logarithmic duration buckets; no request/path/client history |
| Shutdown wait | Up to five seconds after closing sockets and interrupting workers; failure to terminate is surfaced |

This is **not a total process-memory or connection cap**. Thread stacks, JDK
HttpServer state, socket buffers, headers and FileChannel's temporary direct buffers
are separate. On Linux, one secure directory stream owns two OS descriptors; lookup
retains at most two such streams during descent, then one stream plus one body
channel during transfer. The macOS portable path holds no persistent directory
stream. These metadata descriptors are additional to the four asset-body channels.

Executor rejection throws to the JDK dispatcher, which closes that connection before
an HttpExchange reaches the handler. It does not run transfers on the dispatcher,
block for queue space, silently discard a task, or manufacture a 503. Handler-level
asset-unavailable errors return 503/no-store where an exchange is available.

Java 17 has no public per-server request/response timeout or connection-limit API.
The inspected runtime defaults include unlimited active connections and request/
response duration, 200 idle connections and a 30-second idle interval. None of its
JVM-global HTTP properties are changed. Slow headers/readers can occupy all four
workers until disconnect or server shutdown; bounded memory is not a guarantee of
continued availability under indefinitely slow clients.

`close()` stops the server before discarding queued executor tasks, releasing their
connections, then shuts down and awaits workers. Repeated close/start and bind-failure
recovery are tested. On transfer failure, the exchange is closed before the output
stream: JDK17's fixed-length stream close order otherwise left an incomplete response
waiting for more bytes in the regression test. Incomplete bodies are not counted as
successful full responses. Bytes counted by successful OutputStream writes are not
claimed to equal bytes received by a disconnected client.

## Containment and deployment contract

Requests use decoded URI paths, reject traversal and symlinks, and never list
directories. Descriptor-relative no-follow access is used where supported. Temurin
17.0.18 on macOS does not provide SecureDirectoryStream, so the portable path checks
root/relative components and canonical containment, then rechecks metadata before and
after opening and after reading. File key, size, full-resolution modification time
and creation time must match. No body is read before the open checks succeed.

**Stop the server, replace assets, then restart it.** Transparent live replacement
is unsupported. A metadata-preserving in-place edit during a running instance is
not guaranteed to invalidate a validator. Portable Java checks also cannot provide
atomic containment during concurrent ancestor-directory renames; those mutations
violate the immutable-while-serving contract. Detected disappearance, replacement,
truncation or growth aborts/rejects delivery instead of completing stale framing.
The Linux secure backend was source-inspected but is **NOT RUN locally**; Linux CI
and eventual on-device validation remain separate evidence.

## Route, asset and cache policy

Existing paths, GET-only method behaviour, root mapping and planner prefix matching
are preserved. HEAD remains unsupported (405, no body), not a newly added 200 route.
Known extension MIME types are probed once to preserve the runtime's existing media
choices; e.g. this Mac serves JavaScript as `text/javascript`.

| Path / family | Purpose and bytes at base | Response policy |
| --- | --- | --- |
| `/`, `/index.html` | Main page, 30,436 | no-cache + weak ETag |
| `/index.js`, `/index.css` | Main code/style, 189,328 / 26,887 | no-cache + weak ETag |
| `/NT4.js`, `/msgpack.js` | Module dependencies, 17,714 / 17,369 | no-cache + weak ETag |
| `/field-2026.png`, `/Other.png` | Images, 137,703 / 709,598 | no-cache + weak ETag |
| `/runtime-config.html`, `.js`, `.css` | Static profile UI code, 10,159 / 21,153 / 8,758 | no-cache + weak ETag |
| `/rebuilt-spots.json` | Data fetched explicitly with no-store, 4,609 | no-store; no validator |
| `/default-data/*.json` | Joystick/subsystem data, 4,529 / 6,395 | no-store; no validator |
| `/pathplana.package.sample.json` | Sample data, 1,589 | no-store; no validator |
| `/README.md` and other unclassified files | Other content; README 2,620 | no-store; no validator |
| `/planner-autos/index.json` prefix, including query variants | Existing empty manifest | 200, no-store; exact body preserved |
| Other `/planner-autos/` paths | No deployed routines | Existing 404/no-store |
| Missing files, unsupported methods, handler overload/unavailable | Errors | no-store; no validator |

`no-cache` permits storage but requires validation before reuse. No unversioned URL
receives immutable/max-age caching. Weak ETags hash only a per-server random generation
and representation metadata, **not file content**. Restart changes all tags, including
same-size replacements with preserved modification metadata. Query strings do not
create cache entries. Failed metadata checks cannot justify a 304.

If-None-Match supports wildcard, weak/strong comparison, multiple field values and
quoted tag lists (including commas inside opaque tags). Malformed syntax falls back
to body delivery. A matching static GET uses `sendResponseHeaders(304, -1)` with no
body or transfer encoding, and does not open/read the file body. Metadata I/O and
small metadata hashing still occur on a 304.

All existing no-store client fetches remain unchanged. Inherited `/api/joystick-mappings`,
`/api/subsystem-descriptions`, `/api/storage/inventory` and `/api/diagnostics/latest`
requests return 404 because their backends are absent; uploads and writes also remain
unsupported. These were observed before this pass and were not repaired. Optional
favicon requests also find no deployed favicon. NT subscriptions/commands remain a
separate connection and are not served, proxied or modified here.

## Observability

`getMetricsSnapshot()` exposes fixed-size thread-safe observations: broad route counts,
completed full/304/error responses, body bytes, body opens/read operations/read bytes,
executor rejections, I/O failures, active/maximum workers and transfers, observed queue
high-water, and elapsed handler-duration totals/max/histogram. Queue high-water is
sampled after submission and may miss transient occupancy; deterministic saturation
proves the actual configured capacity. Snapshots across concurrently updating counters
are not atomic transactions.

No access logging, per-path keys, disk logs or Logger calls run in HTTP handlers.
There is no robot-periodic or lifecycle logging integration in this pass. The
benchmark samples metrics over the standalone server's stdin/stdout. Existing
AdvantageKit pass-1 fields (`LoggedRobot/FullCycleMS`, `UserCodeMS`, `LogPeriodicMS`,
`GCTimeMS`, `GCCounts` and `Logger/QueuedCycles`) remain unchanged and were not
collected as robot-loop evidence in this HTTP-only test.

## Before/after measurements

Environment: macOS 26.5 arm64, Temurin 17.0.18+8, Gradle 8.11, Python 3.11.9.
Server and client are separate processes. Each of three trials starts a new Java
server with `-Xms64m -Xmx128m`, then performs 20 six-asset warm-up bundles. The same
Python HTTP clients are reused within sequential scenarios; concurrent clients use
the same eight-client design for both versions. The original baseline JAR was
preserved before candidate builds. Candidate results below are from the final
root-check optimization, not the earlier exploratory candidate.

The six-file bundle is `/`, `index.css`, `index.js`, `NT4.js`, `msgpack.js` and
`field-2026.png` (419,437 bytes). Cold/warm/forced scenarios each issue three bundles;
concurrent issues eight bundles. "Cold" means no client validator, not a cold JVM or
filesystem cache. Slow/disconnected uses four nonreading clients against a temporary
16 MiB file, then four abrupt disconnects and one normal recovery GET. It is a finite
stress fixture, not a claim about normal dashboard asset sizes.

CPU values are median **server-process CPU milliseconds per scenario window**, including
measurement-snapshot overhead. Latencies below are client-observed milliseconds:
median of the three runs' nearest-rank p50/p95/p99; maximum is the largest observed
request across those runs. They are not HTTP-worker CPU or robot-loop time.

| Scenario | Body bytes before → after (each run) | CPU ms before → after | Latency p50 before → after | p95 before → after | p99 before → after | Maximum before → after |
| --- | --- | --- | --- | --- | --- | --- |
| Idle, 0.3 s | 0 → 0 | 1.468 → 2.147 | N/A | N/A | N/A | N/A |
| Cold | 1,258,311 → 1,258,311 | 25.688 → 37.728 | 0.754 → 0.730 | 1.290 → 1.798 | 1.290 → 1.798 | 1.892 → 3.174 |
| Warm revalidation | 1,258,311 → 0 | 21.417 → 23.802 | 0.560 → 0.416 | 1.009 → 1.037 | 1.009 → 1.037 | 1.095 → 1.380 |
| Forced reload | 1,258,311 → 1,258,311 | 21.841 → 21.574 | 0.576 → 0.645 | 1.051 → 1.079 | 1.051 → 1.079 | 1.115 → 3.288 |
| Eight concurrent loads | 3,355,496 → 3,355,496 | 54.473 → 55.749 | 1.970 → 1.738 | 5.667 → 4.523 | 7.895 → 5.230 | 7.902 → 7.416 |
| Slow/disconnected + recovery | 30,436 → 30,436* | 182.847 → 19.828 | 10.229 → 1.509 | 10.229 → 1.509 | 10.229 → 1.509 | 14.147 → 1.531 |

\* Only the completed recovery response body is counted; bytes from aborted transfers
are **NOT MEASURED**. Headers, TCP overhead and wire/network byte totals are not included.
Small cohorts make tail estimates coarse: p95/p99 equal the maximum in an 18-request run.

- All three runs had zero reported client/HTTP errors for measured ordinary/recovery
  requests. Deliberately aborted client transfers are accounted separately: candidate
  I/O-failure counters increased by eight per stress window, with one completed recovery
  response. They were not hidden as successful transfers.
- Warm runs produced 18 actual 304s each. Candidate body opens, file read operations
  and file body bytes increased by **zero** in those windows. Full cold/forced runs
  opened 18 bodies and performed 87 bounded reads; concurrent opened 48 and performed
  232. Baseline body-read operations were not instrumented; source shows one
  `readAllBytes` call for each full asset request. There is no file-content hashing.
- Candidate maximum workers/transfers were 4; concurrent queue high-water observed 4.
  Controlled regression saturation reached 4 workers + 8 queued and verified connection
  rejection/recovery. Baseline worker snapshots after concurrency were 6, 6 and 7;
  baseline queue/true peak worker counts were not instrumented.
- During slow-reader snapshots, baseline heap was **102.92 / 69.92 / 102.92 MiB**,
  candidate **17.91 / 17.91 / 16.91 MiB**. RSS was **218.34 / 234.48 / 242.27 MiB**
  versus **75.94 / 61.56 / 75.84 MiB**. These are boundary snapshots, **not peaks**
  or allocation totals. GC counts in slow/disconnect windows were 15/17/18 before
  and 0/0/0 after; recorded GC time was 28/26/32 ms before and zero after.
- The candidate's cold CPU cost is higher in this collection. Additional containment,
  metadata and instrumentation work has a cost. These data support lower retransmission,
  bounded resources and improved slow-client behaviour, **not a uniform CPU reduction**.
  No desktop result is translated into a roboRIO percentage.

## Validation and repeatable commands

**PASS:** original two server tests; **PASS:** final build/format and all **94 tests**
(68 existing, 26 new). New coverage includes byte/MIME/root correctness, empty/missing
files, GET-only methods, planner routes, conditional syntax/304 framing, same-size and
metadata-preserved restart replacements, path/query bounds, symlinks, changing files,
16 KiB streaming, saturation, disconnection, lifecycle and descriptor cleanup.

**PASS:** local Chromium 149 browser checks with NT mocked and nonlocal HTTP/WebSocket
traffic blocked without Playwright routing that would disable browser caching. Initial
load returned full assets; normal reload produced six wire-level 304s; forced reload
returned full assets. Auto/Joystick/Systems/Debug and runtime-config navigation worked.
There were no uncaught page exceptions. Inherited API 404s remain. A temporary asset
replacement followed by server restart on the **same port/URLs** returned fresh 200s
and executed a new JavaScript marker. Product frontend files were never modified.

Executed project commands (using the installed Gradle 8.11 binary and Java 17):

```sh
"$GRADLE_811" test --tests '*OperatorBoardServerTest' jar spotlessCheck --no-daemon --console=plain
"$GRADLE_811" test --tests '*web.*Test' jar --no-daemon --console=plain
"$GRADLE_811" spotlessApply build spotlessCheck --no-daemon --console=plain
python3 -m py_compile tools/performance/web_server_benchmark.py
git diff --check
```

Repeat the benchmark with the same utility, asset tree, runs and warm-up for both JARs:

```sh
python3 tools/performance/web_server_benchmark.py \
  --jar /path/to/baseline/Season2027.jar \
  --assets src/main/deploy/operatorboard --java "$JAVA17/bin/java" \
  --runs 3 --warmup 20 --output /tmp/web-before.json
python3 tools/performance/web_server_benchmark.py \
  --jar build/libs/Season2027.jar \
  --assets src/main/deploy/operatorboard --java "$JAVA17/bin/java" \
  --runs 3 --warmup 20 --output /tmp/web-after.json
```

The utility accepts no host or remote URL: it starts only a loopback server, never
Robot/RobotContainer/NT. Runs are limited to 1–10 and warm-ups to 0–200 bundles. Copied
assets are limited to 512 files/64 MiB with symlinks rejected; the extra stress file is
16 MiB. Socket waits and owned-process shutdown have timeouts. Output records JAR hash,
commands, runtime, inventory and per-trial results. Keep machine-specific raw evidence
outside the product diff.

Pre-existing tests had no failures. Development failures were resolved: unsupported
SecureDirectoryStream on macOS, the incomplete-response close-order regression, and a
source-reviewed Linux descriptor assertion corrected to include the provider's duplicate
FD. Initial browser network-idle waiting timed out on page activity; successful checks
used page load/visible state. Internal implementation review is not Harness independent
PR review. Hosted CI and Linux runtime execution are **NOT RUN at this local checkpoint**.

## Changed files, rollback and hardware follow-up

- `web/OperatorBoardServer.java`: bounded executor/delivery, route policy, validators,
  lifecycle and fixed diagnostics.
- `web/StaticAssetAccess.java`: contained no-follow access and metadata validation.
- `web/StaticAssetValidator.java`: weak metadata tags and conditional-header parsing.
- `web/BoundedAssetTransfer.java`: small-buffer transfer and incomplete-body detection.
- Five new matching web test classes: functional, validator, containment, streaming
  and resource/lifecycle regression coverage.
- `tools/performance/web_server_benchmark.py` and `WebServerBenchmarkMain.java`:
  finite separate-process localhost measurement harness.
- This report. Java web paths are under `src/main/java/org/Griffins1884/frc2027/`.

Rollback only this pass through a new task branch and normal review/validation gates;
do not reset to the audit reference or revert pass 1. After an approved rollback,
a human stops/restarts the server and reloads dashboards. No frontend or persistent
runtime data migration is involved.

A human-operated roboRIO follow-up must first verify approved robot configuration,
current exact code/firmware and normal safety/review prerequisites. Keep the robot
disabled and NT commands isolated. Use human-controlled initial/reload/forced and modest
concurrent HTTP loads; this utility intentionally cannot target a robot. Record existing
AdvantageKit loop/GC/backlog fields, on-device CPU/RSS through approved team tools and
client transfer/latency evidence separately. HTTP snapshots are not automatically
published by this pass; any additional on-robot diagnostics need separate approval.
Separate startup and warmed steady state, repeat comparable workloads, and report median,
p95, p99, maximum and 20 ms loop overruns without summing nested timers. Stop on abnormal
loop timing or dashboard failures. **Hardware performance and deployment readiness are
not established by these desktop tests.**

References: [Java17 HttpServer](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html),
[HttpExchange framing/close](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html),
[OpenJDK17.0.18 dispatcher](https://github.com/openjdk/jdk17u/blob/jdk-17.0.18-ga/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerImpl.java),
[RFC9110 validators](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.1),
[If-None-Match](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2).
