package org.Griffins1884.frc2027.performance;

import edu.wpi.first.networktables.DoubleArrayPublisher;
import edu.wpi.first.networktables.DoubleArraySubscriber;
import edu.wpi.first.networktables.DoubleSubscriber;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.PubSubOption;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/** Separate-process finite NT load; no robot constructors or Driver Station APIs. */
public final class NtLoadMain {
  private static final String[] TELEMETRY_KEYS = {
    "Robot/Performance/SchedulerMS", "Robot/Performance/ContainerMS",
    "Swerve/Performance/PeriodicMS", "Swerve/Performance/OdometryLockWaitMS",
    "Swerve/Performance/OdometryLockHoldMS", "Swerve/Performance/OdometryProcessingMS",
    "Swerve/Performance/InputProcessingAndOdometryMS", "Swerve/Gyro/YawVelocityRadPerSec"
  };

  private NtLoadMain() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> options = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--acknowledge-offhost")) options.put(args[i], "true");
      else {
        if (!args[i].startsWith("--") || i + 1 >= args.length)
          throw new IllegalArgumentException("Expected --key value");
        options.put(args[i], args[++i]);
      }
    }
    String host = options.getOrDefault("--host", "127.0.0.1");
    // Numeric loopback only; resolving arbitrary names is not target discovery or authorization.
    boolean local =
        (host.matches("127\\.[0-9]+\\.[0-9]+\\.[0-9]+") || host.equals("::1"))
            && InetAddress.getByName(host).isLoopbackAddress();
    if (!local
        && !(host.equals(options.get("--allow-host"))
            && options.containsKey("--acknowledge-offhost")))
      throw new IllegalArgumentException(
          "Off-host requires exact --allow-host and --acknowledge-offhost");
    int port = Integer.parseInt(options.getOrDefault("--port", "5810"));
    int clients = Integer.parseInt(options.getOrDefault("--clients", "1"));
    int rate = Integer.parseInt(options.getOrDefault("--updates-per-second", "100"));
    double duration = Double.parseDouble(options.getOrDefault("--duration", "10"));
    String run = options.getOrDefault("--run-id", "local");
    if (!run.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid run ID");
    if (port < 1
        || port > 65535
        || clients < 1
        || clients > 8
        || rate < 0
        || rate > 10000
        || !Double.isFinite(duration)
        || duration <= 0
        || duration > 7200)
      throw new IllegalArgumentException(
          "Limits: clients 1..8, updates 0..10000/s, duration 0..7200s");
    if (!local && rate != 0)
      throw new IllegalArgumentException(
          "Off-host synthetic publishing is prohibited; specify rate 0");
    List<double[]> phases = new ArrayList<>();
    double delay = Double.parseDouble(options.getOrDefault("--ladder-delay", "0"));
    if (!Double.isFinite(delay) || delay < 0 || delay > duration)
      throw new IllegalArgumentException("Invalid ladder delay");
    if (options.containsKey("--ladder")) {
      if (delay > 0) phases.add(new double[] {rate, delay});
      for (String item : options.get("--ladder").split(",")) {
        String[] pair = item.split(":");
        if (pair.length != 2) throw new IllegalArgumentException("Expected rate:hold ladder");
        double stepRate = Double.parseDouble(pair[0]), hold = Double.parseDouble(pair[1]);
        if (!Double.isFinite(stepRate)
            || !Double.isFinite(hold)
            || stepRate < 0
            || stepRate > 10000
            || hold <= 0
            || hold > 7200) throw new IllegalArgumentException("Invalid ladder step");
        phases.add(new double[] {stepRate, hold});
      }
      double used = phases.stream().mapToDouble(p -> p[1]).sum();
      if (phases.size() > 16 || used > duration)
        throw new IllegalArgumentException("Ladder exceeds duration or 16 steps");
      if (used < duration) phases.add(new double[] {0, duration - used});
    } else phases.add(new double[] {rate, duration});
    boolean publishing = phases.stream().anyMatch(p -> p[0] > 0);
    if (!local && publishing)
      throw new IllegalArgumentException("Off-host synthetic ladder prohibited");
    Path output = Path.of(options.getOrDefault("--output", "nt-results.csv"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    List<NetworkTableInstance> instances = new ArrayList<>();
    List<AutoCloseable> handles = new ArrayList<>();
    List<DoubleArraySubscriber[]> subscribers = new ArrayList<>();
    List<DoubleSubscriber> telemetry = new ArrayList<>();
    long[] telemetryCounts = new long[TELEMETRY_KEYS.length];
    boolean[][] telemetryObserved = new boolean[clients][TELEMETRY_KEYS.length];
    DoubleArrayPublisher[] publishers = new DoubleArrayPublisher[32];
    long[] sendTimes = new long[8192];
    long[] ringSequences = new long[8192];
    long[][] lastSequences = new long[clients][32];
    long scheduled = 0,
        published = 0,
        delivered = 0,
        missing = 0,
        localDrops = 0,
        telemetryReceived = 0;
    long rttCount = 0, rttSum = 0, rttMax = 0, ringMisses = 0;
    long start = 0, finish = 0, coordinationNs = 0;
    boolean connected = false;
    var os = ManagementFactory.getOperatingSystemMXBean();
    com.sun.management.OperatingSystemMXBean process =
        os instanceof com.sun.management.OperatingSystemMXBean value ? value : null;
    long cpuStart = process == null ? -1 : process.getProcessCpuTime();
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write(
          "kind,scheduled_ns,start_ns,end_ns,status,bytes,latency_ms,scheduling_delay_ms,detail\n");
      if (publishing) {
        NetworkTableInstance publisher = connect(host, port, "match-publisher-" + run);
        instances.add(publisher);
        for (int t = 0; t < 32; t++) {
          publishers[t] =
              publisher
                  .getDoubleArrayTopic("/Benchmark/" + run + "/sample" + t)
                  .publish(
                      PubSubOption.periodic(0.02),
                      PubSubOption.sendAll(true),
                      PubSubOption.keepDuplicates(true));
          handles.add(publishers[t]);
        }
      }
      for (int c = 0; c < clients; c++) {
        NetworkTableInstance subscriber = connect(host, port, "match-subscriber-" + run + "-" + c);
        instances.add(subscriber);
        DoubleArraySubscriber[] topics = new DoubleArraySubscriber[32];
        if (publishing)
          for (int t = 0; t < 32; t++) {
            topics[t] =
                subscriber
                    .getDoubleArrayTopic("/Benchmark/" + run + "/sample" + t)
                    .subscribe(
                        new double[0],
                        PubSubOption.periodic(0.02),
                        PubSubOption.sendAll(true),
                        PubSubOption.keepDuplicates(true),
                        PubSubOption.pollStorage(64));
            handles.add(topics[t]);
          }
        subscribers.add(topics);
        for (String key : TELEMETRY_KEYS) {
          DoubleSubscriber sensor =
              subscriber
                  .getDoubleTopic("/AdvantageKit/RealOutputs/" + key)
                  .subscribe(
                      Double.NaN,
                      PubSubOption.periodic(0.05),
                      PubSubOption.sendAll(false),
                      PubSubOption.pollStorage(64));
          handles.add(sensor);
          telemetry.add(sensor);
        }
      }
      long connectDeadline = System.nanoTime() + 5_000_000_000L;
      while (System.nanoTime() < connectDeadline
          && instances.stream().anyMatch(i -> !i.isConnected())) LockSupport.parkNanos(10_000_000);
      connected = instances.stream().allMatch(NetworkTableInstance::isConnected);
      double warmup = Double.parseDouble(options.getOrDefault("--warmup-seconds", "0"));
      if (!Double.isFinite(warmup) || warmup < 0 || warmup > 10)
        throw new IllegalArgumentException("Warm-up seconds must be 0..10");
      long handshakeStart = System.nanoTime();
      if (publishing && connected) {
        for (DoubleArrayPublisher publisher : publishers) publisher.set(new double[] {0, 0});
        long handshakeDeadline = System.nanoTime() + 5_000_000_000L;
        boolean received = false;
        while (!received && System.nanoTime() < handshakeDeadline) {
          received = true;
          for (DoubleArraySubscriber[] client : subscribers)
            for (DoubleArraySubscriber topic : client)
              if (topic.get().length != 2) received = false;
          if (!received) LockSupport.parkNanos(5_000_000L);
        }
        if (!received)
          throw new IllegalStateException(
              "Synthetic subscriber handshake did not reach every topic/client");
      }
      while (System.nanoTime() - handshakeStart < (long) (warmup * 1e9))
        LockSupport.parkNanos(5_000_000L);
      if (publishing)
        for (DoubleArraySubscriber[] client : subscribers)
          for (DoubleArraySubscriber topic : client) topic.readQueue();
      for (DoubleSubscriber topic : telemetry) topic.readQueue();
      if (options.containsKey("--ready-file")) {
        Path ready = Path.of(options.get("--ready-file"));
        Files.createDirectories(ready.toAbsolutePath().getParent());
        Path temporary =
            ready.resolveSibling(ready.getFileName() + ".tmp." + ProcessHandle.current().pid());
        Files.writeString(
            temporary,
            "{\"ready\":true,\"connected\":" + connected + ",\"warmup_seconds\":" + warmup + "}");
        Files.move(
            temporary,
            ready,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      if (options.containsKey("--start-marker")) {
        Path marker = Path.of(options.get("--start-marker"));
        long startDeadline = System.nanoTime() + 60_000_000_000L;
        while (!Files.isRegularFile(marker)) {
          if (System.nanoTime() >= startDeadline)
            throw new IllegalStateException("Start marker not received within 60 seconds");
          LockSupport.parkNanos(5_000_000L);
        }
      }
      start = System.nanoTime();
      coordinationNs = start - handshakeStart;
      long deadline = start + (long) (duration * 1e9);
      long nextSummary = start + 1_000_000_000L;
      long nextPoll = start;
      long total = (long) phases.stream().mapToDouble(p -> p[0] * p[1]).sum();
      while (System.nanoTime() < deadline + 250_000_000L || scheduled < total) {
        long now = System.nanoTime();
        if (publishing && scheduled < total) {
          long offeredNow = offered(phases, Math.max(0, (now - start) / 1e9));
          if (offeredNow > scheduled) {
            // Bound catch-up, recording work the generator could not offer on time.
            if (offeredNow > scheduled + 64) {
              long skipped = offeredNow - scheduled - 1;
              localDrops += skipped;
              scheduled += skipped;
            }
            while (scheduled < offeredNow) {
              long sequence = ++scheduled;
              int topic = (int) ((sequence - 1) % 32);
              int slot = (int) (sequence % 8192);
              sendTimes[slot] = now;
              ringSequences[slot] = sequence;
              publishers[topic].set(new double[] {sequence, Math.sin(sequence * 0.01)});
              published++;
            }
          }
        }
        if (now >= nextPoll) {
          nextPoll = now + 5_000_000L;
          for (int c = 0; c < clients; c++) {
            if (publishing)
              for (int t = 0; t < 32; t++) {
                for (double[] sample : subscribers.get(c)[t].readQueueValues()) {
                  if (sample.length != 2 || !Double.isFinite(sample[0])) {
                    missing++;
                    continue;
                  }
                  long sequence = (long) sample[0];
                  long previous = lastSequences[c][t];
                  if (sequence <= previous) continue;
                  if (previous > 0 && sequence > previous + 32)
                    missing += (sequence - previous) / 32 - 1;
                  lastSequences[c][t] = sequence;
                  delivered++;
                  int slot = (int) (sequence % 8192);
                  if (slot >= 0 && ringSequences[slot] == sequence) {
                    long rtt = System.nanoTime() - sendTimes[slot];
                    rttCount++;
                    rttSum += rtt;
                    rttMax = Math.max(rttMax, rtt);
                  } else ringMisses++;
                }
              }
            for (int key = 0; key < TELEMETRY_KEYS.length; key++) {
              DoubleSubscriber sensor = telemetry.get(c * TELEMETRY_KEYS.length + key);
              long received = sensor.readQueue().length;
              telemetryReceived += received;
              telemetryCounts[key] += received;
              telemetryObserved[c][key] |= sensor.getAtomic().timestamp != 0;
            }
          }
        }
        if (now >= nextSummary) {
          writer.write(
              "nt,"
                  + start
                  + ","
                  + start
                  + ","
                  + now
                  + ",SUMMARY,"
                  + (published * 16)
                  + ",,,published="
                  + published
                  + ";delivered="
                  + delivered
                  + ";telemetryReceived="
                  + telemetryReceived
                  + ";gaps="
                  + missing
                  + ";localDrops="
                  + localDrops
                  + "\n");
          writer.flush();
          nextSummary = now + 1_000_000_000L;
        }
        LockSupport.parkNanos(1_000_000);
      }
      finish = System.nanoTime();
    } finally {
      for (AutoCloseable handle : handles)
        try {
          handle.close();
        } catch (Exception ignored) {
        }
      for (NetworkTableInstance instance : instances) {
        instance.stopClient();
        instance.close();
      }
    }
    long cpuEnd = process == null ? -1 : process.getProcessCpuTime();
    long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    String cpu = cpuStart < 0 || cpuEnd < 0 ? "null" : Long.toString(cpuEnd - cpuStart);
    int telemetryMissing = 0;
    StringBuilder perKey = new StringBuilder("{");
    for (int key = 0; key < TELEMETRY_KEYS.length; key++) {
      int absent = 0;
      for (int client = 0; client < clients; client++)
        if (!telemetryObserved[client][key]) absent++;
      telemetryMissing += absent;
      if (key > 0) perKey.append(',');
      perKey
          .append('"')
          .append(TELEMETRY_KEYS[key])
          .append("\":{\"delivered\":")
          .append(telemetryCounts[key])
          .append(",\"clients_missing\":")
          .append(absent)
          .append('}');
    }
    perKey.append('}');
    boolean valid =
        connected
            && localDrops == 0
            && telemetryReceived > 0
            && telemetryMissing == 0
            && (!publishing || completeDelivery(scheduled, published, delivered, clients, missing));
    String summary =
        "{\"kind\":\"nt\",\"generator_cpu_scope\":\"startup_through_shutdown\",\"coordination_ns\":"
            + coordinationNs
            + ",\"offered_start_monotonic_ns\":"
            + start
            + ",\"offered_stop_monotonic_ns\":"
            + (start + (long) (duration * 1e9))
            + ",\"connected\":"
            + connected
            + ",\"scheduled\":"
            + scheduled
            + ",\"published\":"
            + published
            + ",\"delivered\":"
            + delivered
            + ",\"expected_deliveries\":"
            + (published * clients)
            + ",\"undelivered\":"
            + Math.max(0, published * clients - delivered)
            + ",\"offered_updates_per_second\":"
            + rate
            + ",\"achieved_publish_updates_per_second\":"
            + (published / ((finish - start) / 1e9))
            + ",\"sequence_gaps\":"
            + missing
            + ",\"locally_dropped\":"
            + localDrops
            + ",\"active_window_achieved_publish_updates_per_second\":"
            + (published / duration)
            + ",\"telemetry_periodic_s\":0.05,\"telemetry_send_all\":false,\"telemetry_per_key\":"
            + perKey
            + ",\"telemetry_missing_subscriptions\":"
            + telemetryMissing
            + ",\"telemetry_received\":"
            + telemetryReceived
            + ",\"rtt_samples\":"
            + rttCount
            + ",\"rtt_mean_ms\":"
            + (rttCount == 0 ? "null" : Double.toString(rttSum / 1e6 / rttCount))
            + ",\"rtt_max_ms\":"
            + (rttCount == 0 ? "null" : Double.toString(rttMax / 1e6))
            + ",\"ring_misses\":"
            + ringMisses
            + ",\"generator_cpu_ns\":"
            + cpu
            + ",\"heap_used_bytes\":"
            + heap
            + ",\"delivery_drain_s\":0.25,\"offered_duration_s\":"
            + duration
            + ",\"duration_s\":"
            + ((finish - start) / 1e9)
            + ",\"clients\":"
            + clients
            + ",\"topics\":32,\"payload_bytes\":16,\"poll_storage\":64,\"periodic_s\":0.02,\"send_all\":true"
            + ",\"valid\":"
            + valid
            + "}";
    String name = output.getFileName().toString().replaceFirst("\\.csv$", "") + ".summary.json";
    Files.writeString(output.resolveSibling(name), summary + "\n");
    System.out.println(summary);
    if (!valid) System.exit(2);
  }

  static boolean completeDelivery(
      long scheduled, long published, long delivered, int clients, long gaps) {
    return scheduled >= 0
        && published == scheduled
        && clients > 0
        && delivered == published * clients
        && gaps == 0;
  }

  private static long offered(List<double[]> phases, double elapsed) {
    double count = 0;
    for (double[] phase : phases) {
      double used = Math.min(elapsed, phase[1]);
      count += Math.max(0, used) * phase[0];
      elapsed -= used;
      if (elapsed <= 0) break;
    }
    return (long) count;
  }

  private static NetworkTableInstance connect(String host, int port, String identity) {
    NetworkTableInstance instance = NetworkTableInstance.create();
    instance.setServer(host, port);
    instance.startClient4(identity);
    return instance;
  }
}
