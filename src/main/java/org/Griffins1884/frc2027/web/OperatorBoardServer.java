package org.Griffins1884.frc2027.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Filesystem;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.Griffins1884.frc2027.Config;

/**
 * Bounded HTTP delivery for the existing dashboard. NetworkTables remains a separate connection.
 * Deploy assets by stopping, replacing, and restarting this server; live edits are unsupported.
 */
public final class OperatorBoardServer implements AutoCloseable {
  static final int WORKERS = 4;
  static final int QUEUE_CAPACITY = 8;
  static final int LISTEN_BACKLOG = 16;
  static final int TRANSFER_BUFFER_BYTES = 16 * 1024;
  private static final byte[] EMPTY_AUTO_MANIFEST =
      "{\"version\":\"2027.0\",\"generator\":\"Season2027\",\"autos\":[]}"
          .getBytes(StandardCharsets.UTF_8);
  private static final Set<String> REVALIDATED_ASSETS =
      Set.of(
          "index.html",
          "index.js",
          "index.css",
          "NT4.js",
          "msgpack.js",
          "field-2026.png",
          "Other.png",
          "runtime-config.html",
          "runtime-config.js",
          "runtime-config.css");
  private static final Map<String, String> KNOWN_TYPES = knownTypes();
  private static final AtomicInteger SERVER_IDS = new AtomicInteger();

  private final StaticAssetAccess assets;
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final String generation = UUID.randomUUID().toString();
  private final LongAdder requests = new LongAdder();
  private final LongAdder staticRequests = new LongAdder();
  private final LongAdder plannerRequests = new LongAdder();
  private final LongAdder fullResponses = new LongAdder();
  private final LongAdder notModifiedResponses = new LongAdder();
  private final LongAdder errorResponses = new LongAdder();
  private final LongAdder bodyBytes = new LongAdder();
  private final LongAdder fileBodyOpens = new LongAdder();
  private final LongAdder fileReadOperations = new LongAdder();
  private final LongAdder fileBytesRead = new LongAdder();
  private final LongAdder rejectedTasks = new LongAdder();
  private final LongAdder ioFailures = new LongAdder();
  private final LongAdder durationNanos = new LongAdder();
  private final AtomicLong maxDurationNanos = new AtomicLong();
  private final AtomicLongArray durationBuckets = new AtomicLongArray(16);
  private final AtomicInteger queueHighWater = new AtomicInteger();
  private final AtomicInteger activeTransfers = new AtomicInteger();
  private final AtomicInteger transferHighWater = new AtomicInteger();

  private OperatorBoardServer(Path contentRoot, String bindAddress, int port) throws IOException {
    assets = new StaticAssetAccess(contentRoot);
    server = HttpServer.create(new InetSocketAddress(bindAddress, port), LISTEN_BACKLOG);
    int serverId = SERVER_IDS.incrementAndGet();
    AtomicInteger workerIds = new AtomicInteger();
    executor =
        new ThreadPoolExecutor(
            WORKERS,
            WORKERS,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            task -> {
              Thread thread =
                  new Thread(
                      task, "OperatorBoardHttp-" + serverId + "-" + workerIds.incrementAndGet());
              thread.setDaemon(true);
              thread.setPriority(Thread.NORM_PRIORITY);
              return thread;
            },
            (task, pool) -> {
              rejectedTasks.increment();
              // JDK17's dispatcher catches this and closes the unparsed connection. No exchange
              // is available here, so this is deliberately not represented as an HTTP 503.
              throw new RejectedExecutionException("Operator board HTTP capacity exhausted");
            });
    try {
      server.setExecutor(
          task -> {
            try {
              executor.execute(task);
            } finally {
              queueHighWater.accumulateAndGet(executor.getQueue().size(), Math::max);
            }
          });
      server.createContext("/", this::handle);
    } catch (RuntimeException | Error exception) {
      server.stop(0);
      executor.shutdownNow();
      throw exception;
    }
  }

  public static OperatorBoardServer startDefault() {
    if (!Config.WebUIConfig.ENABLED) return null;
    try {
      return start(
          Filesystem.getDeployDirectory().toPath().resolve("operatorboard"),
          Config.WebUIConfig.BIND_ADDRESS,
          Config.WebUIConfig.PORT);
    } catch (IOException exception) {
      DriverStation.reportError(
          "Failed to start operator board web server", exception.getStackTrace());
      return null;
    }
  }

  /** A port of zero selects an ephemeral port; tests and benchmarks bind only to loopback. */
  public static OperatorBoardServer start(Path contentRoot, String bindAddress, int port)
      throws IOException {
    OperatorBoardServer result = new OperatorBoardServer(contentRoot, bindAddress, port);
    try {
      result.server.start();
      return result;
    } catch (RuntimeException | Error exception) {
      result.close();
      throw exception;
    }
  }

  public int getPort() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    server.stop(0); // Close active AND queued connections before discarding queued executor tasks.
    executor.shutdownNow();
    boolean interrupted = false;
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!executor.isTerminated() && System.nanoTime() < deadline) {
        try {
          executor.awaitTermination(
              Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (!executor.isTerminated())
      throw new IllegalStateException("HTTP workers did not terminate during shutdown");
  }

  private void handle(HttpExchange exchange) throws IOException {
    long start = System.nanoTime();
    requests.increment();
    try (exchange) {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "GET");
        sendText(exchange, 405, "Method not allowed");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      // Preserve HttpServer's previous context prefix matching, including manifest suffixes.
      if (path.startsWith("/planner-autos/index.json")) {
        plannerRequests.increment();
        send(exchange, 200, "application/json; charset=utf-8", EMPTY_AUTO_MANIFEST);
      } else if (path.startsWith("/planner-autos/")) {
        plannerRequests.increment();
        sendText(exchange, 404, "No 2027 autonomous routines are deployed.");
      } else {
        staticRequests.increment();
        serveAsset(exchange, path);
      }
    } catch (IOException exception) {
      ioFailures.increment();
      // Headers may already have been sent. Closing aborts an incomplete fixed-length body;
      // do not append an error response or count this as a completed 200.
    } finally {
      long elapsed = System.nanoTime() - start;
      durationNanos.add(elapsed);
      maxDurationNanos.accumulateAndGet(elapsed, Math::max);
      long micros = Math.max(1, elapsed / 1000);
      int bucket = Math.min(15, 63 - Long.numberOfLeadingZeros(micros));
      durationBuckets.incrementAndGet(bucket);
    }
  }

  private void serveAsset(HttpExchange exchange, String path) throws IOException {
    StaticAssetAccess.Asset asset;
    try {
      asset = assets.inspect(path);
    } catch (NoSuchFileException | AccessDeniedException | InvalidPathException exception) {
      sendText(exchange, 404, "Not found");
      return;
    } catch (IOException exception) {
      sendText(exchange, 503, "Asset unavailable");
      return;
    }
    try (asset) {
      boolean revalidate = REVALIDATED_ASSETS.contains(asset.relativePath().toString());
      String etag =
          revalidate
              ? StaticAssetValidator.tag(generation, asset.relativePath(), asset.attributes())
              : null;
      if (revalidate
          && StaticAssetValidator.matches(
              exchange.getRequestHeaders().get("If-None-Match"), etag)) {
        if (!asset.unchanged()) {
          sendText(exchange, 503, "Asset changed; retry after deployment");
          return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.sendResponseHeaders(304, -1);
        notModifiedResponses.increment();
        return;
      }
      SeekableByteChannel input;
      try {
        input = asset.openBody();
      } catch (NoSuchFileException | AccessDeniedException exception) {
        sendText(exchange, 404, "Not found");
        return;
      } catch (IOException exception) {
        sendText(exchange, 503, "Asset unavailable");
        return;
      }
      fileBodyOpens.increment();
      int active = activeTransfers.incrementAndGet();
      transferHighWater.accumulateAndGet(active, Math::max);
      try (input) {
        long size = asset.attributes().size();
        exchange.getResponseHeaders().set("Content-Type", contentType(asset.relativePath()));
        exchange.getResponseHeaders().set("Cache-Control", revalidate ? "no-cache" : "no-store");
        if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
        if (size == 0) {
          if (!asset.unchanged()) throw new IOException("Empty asset changed");
          exchange.getResponseHeaders().set("Content-Length", "0");
          exchange.sendResponseHeaders(200, -1);
        } else {
          byte[] buffer = new byte[TRANSFER_BUFFER_BYTES];
          exchange.sendResponseHeaders(200, size);
          OutputStream output = exchange.getResponseBody();
          // Close the exchange first on failure. In JDK17, prematurely closing the fixed-length
          // output stream itself can mark it closed before Exchange.close aborts its connection.
          BoundedAssetTransfer.copy(
              input,
              output,
              size,
              buffer,
              () -> input.size() == size && asset.unchanged(),
              fileReadOperations::increment,
              fileBytesRead::add,
              bodyBytes::add);
          output.flush();
          exchange.close();
        }
        fullResponses.increment();
      } finally {
        activeTransfers.decrementAndGet();
      }
    }
  }

  private static Map<String, String> knownTypes() {
    // Probe a fixed set once, retaining platform MIME choices without probing every request.
    var types = new java.util.HashMap<String, String>();
    var fallback =
        Map.of(
            "html",
            "text/html; charset=utf-8",
            "js",
            "application/javascript",
            "css",
            "text/css",
            "json",
            "application/json",
            "png",
            "image/png");
    fallback.forEach(
        (extension, type) -> {
          try {
            String detected = Files.probeContentType(Path.of("asset." + extension));
            types.put(extension, detected != null ? detected : type);
          } catch (IOException exception) {
            types.put(extension, type);
          }
        });
    return Map.copyOf(types);
  }

  private static String contentType(Path path) throws IOException {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String known = dot < 0 ? null : KNOWN_TYPES.get(name.substring(dot + 1));
    if (known != null) return known;
    String detected = Files.probeContentType(path);
    return detected != null ? detected : "application/octet-stream";
  }

  private void sendText(HttpExchange exchange, int status, String body) throws IOException {
    send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  private void send(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(status, -1);
      if (status >= 400) errorResponses.increment();
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    OutputStream output = exchange.getResponseBody();
    output.write(body);
    output.flush();
    bodyBytes.add(body.length);
    exchange.close();
    if (status < 400) fullResponses.increment();
    else errorResponses.increment();
  }

  /** Constant-size, thread-safe observations; durations include file I/O and slow-client waits. */
  public MetricsSnapshot getMetricsSnapshot() {
    long[] buckets = new long[16];
    for (int i = 0; i < buckets.length; i++) buckets[i] = durationBuckets.get(i);
    return new MetricsSnapshot(
        requests.sum(),
        staticRequests.sum(),
        plannerRequests.sum(),
        fullResponses.sum(),
        notModifiedResponses.sum(),
        errorResponses.sum(),
        bodyBytes.sum(),
        fileBodyOpens.sum(),
        fileReadOperations.sum(),
        fileBytesRead.sum(),
        rejectedTasks.sum(),
        ioFailures.sum(),
        executor.getActiveCount(),
        executor.getQueue().size(),
        executor.getLargestPoolSize(),
        queueHighWater.get(),
        activeTransfers.get(),
        transferHighWater.get(),
        TRANSFER_BUFFER_BYTES,
        0,
        durationNanos.sum(),
        maxDurationNanos.get(),
        buckets);
  }

  public record MetricsSnapshot(
      long requests,
      long staticRequests,
      long plannerRequests,
      long fullResponses,
      long notModifiedResponses,
      long errorResponses,
      long bodyBytes,
      long fileBodyOpens,
      long fileReadOperations,
      long fileBytesRead,
      long rejectedTasks,
      long ioFailures,
      int activeWorkers,
      int queuedTasks,
      int workerHighWater,
      int queueHighWater,
      int activeTransfers,
      int transferHighWater,
      int transferBufferBytes,
      int validatorCacheEntries,
      long durationNanos,
      long maxDurationNanos,
      long[] durationBuckets) {
    public MetricsSnapshot {
      durationBuckets = durationBuckets.clone();
    }

    @Override
    public long[] durationBuckets() {
      return durationBuckets.clone();
    }
  }
}
