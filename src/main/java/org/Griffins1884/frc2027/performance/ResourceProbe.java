package org.Griffins1884.frc2027.performance;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Portable JVM probes. Unsupported CPU values remain null, never a fabricated zero. */
public final class ResourceProbe {
  private ResourceProbe() {}

  public record Snapshot(
      long monotonicNanos,
      Long processCpuNanos,
      Long currentThreadCpuNanos,
      long heapUsedBytes,
      long heapCommittedBytes,
      long heapMaxBytes,
      int threads,
      int availableProcessors,
      Long gcCollections,
      Long gcTimeMillis,
      List<ThreadSample> selectedThreads,
      boolean threadCpuAvailable,
      boolean selectedThreadsTruncated) {}

  /** Cumulative CPU for one thread identity; sum only matching IDs across consecutive samples. */
  public record ThreadSample(long threadId, String group, Long cpuNanos) {}

  static String threadGroup(long id, String name, long currentId) {
    if (id == currentId) return "main_loop";
    if (name.startsWith("OperatorBoardHttp-")) return "http";
    if (name.equals("SwerveConfiguration")) return "configuration";
    if (name.equals("PhoenixOdometryThread")) return "odometry";
    if (name.equals("AdvantageKit_LogReceiver")) return "log_receiver";
    return null;
  }

  public static String sourceForThreadGroup(String group) {
    return switch (group) {
      case "main_loop" -> "performance/ObservedRobot.java (inclusive robot/scheduler loop)";
      case "http" -> "web/OperatorBoardServer.java";
      case "configuration" -> "subsystems/swerve/ModuleConfigurationWorker.java";
      case "odometry" -> "subsystems/swerve/PhoenixOdometryThread.java";
      case "log_receiver" -> "akit-java:26.0.2/ReceiverThread.java (all configured receivers)";
      default -> "UNAVAILABLE";
    };
  }

  public static Snapshot sample() {
    return sample(false);
  }

  /** Detailed Java thread probes do not enable JVM monitoring or enumerate native-only threads. */
  public static Snapshot sample(boolean includeSelectedThreads) {
    var os = ManagementFactory.getOperatingSystemMXBean();
    Long process = null;
    if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
      process = readCpu(extended::getProcessCpuTime);
    }
    var thread = ManagementFactory.getThreadMXBean();
    Long threadCpu =
        thread.isCurrentThreadCpuTimeSupported() && thread.isThreadCpuTimeEnabled()
            ? readCpu(thread::getCurrentThreadCpuTime)
            : null;
    List<ThreadSample> selected = new ArrayList<>();
    boolean truncated = false;
    boolean threadCpuAvailable =
        thread.isThreadCpuTimeSupported() && thread.isThreadCpuTimeEnabled();
    try {
      if (includeSelectedThreads && threadCpuAvailable) {
        long[] ids = thread.getAllThreadIds();
        // The JDK allocates its ID snapshot; application work/history remain explicitly bounded.
        int scanned = Math.min(ids.length, 512);
        truncated = ids.length > scanned;
        for (int i = 0; i < scanned; i++) {
          var info = thread.getThreadInfo(ids[i]);
          if (info == null) continue;
          String group = threadGroup(ids[i], info.getThreadName(), Thread.currentThread().getId());
          if (group == null) continue;
          if (selected.size() == 64) {
            truncated = true;
            break;
          }
          selected.add(new ThreadSample(ids[i], group, available(thread.getThreadCpuTime(ids[i]))));
        }
      }
    } catch (UnsupportedOperationException | SecurityException unsupported) {
      threadCpuAvailable = false;
      selected.clear();
    }
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    Long gcCount = 0L;
    Long gcMillis = 0L;
    for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      long count = gc.getCollectionCount();
      long millis = gc.getCollectionTime();
      gcCount = gcCount == null || count < 0 ? null : gcCount + count;
      gcMillis = gcMillis == null || millis < 0 ? null : gcMillis + millis;
    }
    return new Snapshot(
        System.nanoTime(),
        process,
        threadCpu,
        heap.getUsed(),
        heap.getCommitted(),
        heap.getMax(),
        thread.getThreadCount(),
        os.getAvailableProcessors(),
        gcCount,
        gcMillis,
        List.copyOf(selected),
        includeSelectedThreads && threadCpuAvailable,
        truncated);
  }

  static Long readCpu(java.util.function.LongSupplier supplier) {
    try {
      return available(supplier.getAsLong());
    } catch (UnsupportedOperationException | SecurityException unsupported) {
      return null;
    }
  }

  static Long available(long value) {
    return value < 0 ? null : value;
  }
}
