import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.Griffins1884.frc2027.web.OperatorBoardServer;

/** Standalone localhost fixture: never starts Robot, RobotContainer, or NetworkTables. */
public final class WebServerBenchmarkMain {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("contentRoot required");
    try (var server = OperatorBoardServer.start(Path.of(args[0]), "127.0.0.1", 0);
        var in = new BufferedReader(new InputStreamReader(System.in))) {
      System.out.println("PORT=" + server.getPort());
      System.out.flush();
      String line;
      while ((line = in.readLine()) != null && !line.equals("stop")) {
        if (!line.equals("metrics")) continue;
        var os = (com.sun.management.OperatingSystemMXBean)
            ManagementFactory.getOperatingSystemMXBean();
        long gcCount = 0;
        long gcMs = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
          gcCount += Math.max(0, gc.getCollectionCount());
          gcMs += Math.max(0, gc.getCollectionTime());
        }
        long workers = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("pool-")
                || t.getName().startsWith("OperatorBoardHttp-"))
            .count();
        var json = new StringBuilder("{\"cpuNs\":").append(os.getProcessCpuTime())
            .append(",\"heapUsed\":")
            .append(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed())
            .append(",\"gcCount\":").append(gcCount)
            .append(",\"gcMs\":").append(gcMs)
            .append(",\"workers\":").append(workers);
        // Public application API only; allows the identical runner to test the older server.
        try {
          Object metrics = server.getClass().getMethod("getMetricsSnapshot").invoke(server);
          for (var component : metrics.getClass().getRecordComponents()) {
            Object value = component.getAccessor().invoke(metrics);
            if (value instanceof Number) {
              json.append(",\"http_").append(component.getName()).append("\":").append(value);
            }
          }
        } catch (NoSuchMethodException baselineWithoutMetrics) {
          // Baseline lacks application counters. Do not invent comparable counter values.
        }
        System.out.println(json.append('}'));
        System.out.flush();
      }
    }
  }
}
