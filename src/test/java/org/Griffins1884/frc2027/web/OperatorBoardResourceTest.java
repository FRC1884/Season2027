package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBoardResourceTest {
  @TempDir Path root;

  @Test
  void saturatedExecutorBoundsQueuedWorkClosesExcessAndRecovers() throws Exception {
    Files.writeString(root.resolve("index.js"), "streamable");
    CountDownLatch workersStarted = new CountDownLatch(4), release = new CountDownLatch(1);
    List<Socket> queued = new ArrayList<>();
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      ThreadPoolExecutor executor = executor(server);
      try {
        for (int i = 0; i < 4; i++)
          executor.execute(
              () -> {
                assertEquals(Thread.NORM_PRIORITY, Thread.currentThread().getPriority());
                workersStarted.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
        assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 8; i++) queued.add(request(server, "/index.js", false));
        await(() -> server.getMetricsSnapshot().queuedTasks() == 8);
        var full = server.getMetricsSnapshot();
        assertEquals(4, full.activeWorkers());
        assertEquals(4, full.workerHighWater());
        assertEquals(8, full.queueHighWater());
        assertEquals(0, full.fileBodyOpens());
        assertEquals(0, full.activeTransfers());
        try (Socket rejected = request(server, "/index.js", false)) {
          try {
            assertEquals(
                -1,
                rejected.getInputStream().read(),
                "Executor rejection closes the connection, not a503 body");
          } catch (SocketTimeoutException timeout) {
            throw new AssertionError("Rejected connection was left hanging", timeout);
          } catch (SocketException reset) {
            /* Connection reset is also JDK rejection behavior. */
          }
        }
        assertTrue(server.getMetricsSnapshot().rejectedTasks() >= 1);
        release.countDown();
        for (Socket socket : queued) {
          String response =
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(response.contains("200 OK"));
          assertTrue(response.endsWith("streamable"));
          socket.close();
        }
        await(() -> server.getMetricsSnapshot().activeTransfers() == 0);
        try (Socket recovered = request(server, "/index.js", false)) {
          assertTrue(
              new String(recovered.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                  .endsWith("streamable"));
        }
        assertTrue(server.getMetricsSnapshot().transferHighWater() <= 4);
        assertEquals(16384, server.getMetricsSnapshot().transferBufferBytes());
      } finally {
        release.countDown();
        for (Socket socket : queued) socket.close();
      }
    }
  }

  @Test
  void slowReadersStayBoundedAndDisconnectsReleaseTransfers() throws Exception {
    largeFile();
    List<Socket> slow = new ArrayList<>();
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      try {
        for (int i = 0; i < 4; i++) slow.add(request(server, "/large.bin", true));
        await(
            () ->
                server.getMetricsSnapshot().activeTransfers() == 4
                    && server.getMetricsSnapshot().bodyBytes() > 0);
        var snapshot = server.getMetricsSnapshot();
        assertEquals(4, snapshot.transferHighWater());
        assertEquals(0, snapshot.fullResponses());
        assertEquals(4, snapshot.fileBodyOpens());
        for (Socket socket : slow) socket.close();
        // Transfer cleanup precedes the enclosing handler's failure counter. Wait for both.
        await(
            () ->
                server.getMetricsSnapshot().activeTransfers() == 0
                    && server.getMetricsSnapshot().ioFailures() >= 4);
        assertEquals(0, server.getMetricsSnapshot().fullResponses());
        assertTrue(server.getMetricsSnapshot().ioFailures() >= 4);
        Files.writeString(root.resolve("index.html"), "recovered");
        try (Socket socket = request(server, "/", false)) {
          assertTrue(
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                  .endsWith("recovered"));
        }
      } finally {
        for (Socket socket : slow) socket.close();
      }
    }
  }

  @Test
  void disappearingAssetAbortsBeforeACompleteDeclaredBody() throws Exception {
    Path file = largeFile();
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0);
        Socket socket = request(server, "/large.bin", false)) {
      await(
          () ->
              server.getMetricsSnapshot().activeTransfers() == 1
                  && server.getMetricsSnapshot().bodyBytes() > 0);
      Files.delete(file);
      InputStream input = socket.getInputStream();
      String headers = readHeaders(input);
      assertTrue(headers.startsWith("HTTP/1.1 200"));
      assertTrue(headers.toLowerCase(Locale.ROOT).contains("content-length: 16777216"));
      long received = 0;
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) received += count;
      assertTrue(received < 16L * 1024 * 1024, "A changed response must not look complete");
      await(() -> server.getMetricsSnapshot().activeTransfers() == 0);
      assertEquals(0, server.getMetricsSnapshot().fullResponses());
      assertEquals(1, server.getMetricsSnapshot().ioFailures());
    }
  }

  @Test
  void startupFailureAndRepeatedCloseReleaseSocketsAndThreads() throws Exception {
    Files.writeString(root.resolve("index.html"), "ok");
    for (int i = 0; i < 5; i++) {
      OperatorBoardServer server = OperatorBoardServer.start(root, "127.0.0.1", 0);
      int port = server.getPort();
      assertThrows(IOException.class, () -> OperatorBoardServer.start(root, "127.0.0.1", port));
      try (Socket request = request(server, "/", false)) {
        assertTrue(
            new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .endsWith("ok"));
      }
      server.close();
      server.close();
      assertTrue(executor(server).isTerminated());
      try (var next = OperatorBoardServer.start(root, "127.0.0.1", port)) {
        assertEquals(port, next.getPort());
      }
    }
    assertFalse(
        Thread.getAllStackTraces().keySet().stream()
            .anyMatch(t -> t.isAlive() && t.getName().startsWith("OperatorBoardHttp-")));
  }

  @Test
  void closeUnblocksAnActiveSlowTransferAndDiscardsQueuedConnections() throws Exception {
    largeFile();
    List<Socket> sockets = new ArrayList<>();
    var server = OperatorBoardServer.start(root, "127.0.0.1", 0);
    try {
      for (int i = 0; i < 4; i++) sockets.add(request(server, "/large.bin", true));
      await(() -> server.getMetricsSnapshot().activeTransfers() == 4);
      for (int i = 0; i < 8; i++) sockets.add(request(server, "/large.bin", true));
      await(() -> server.getMetricsSnapshot().queuedTasks() == 8);
      server.close();
      assertTrue(executor(server).isTerminated());
      assertEquals(0, server.getMetricsSnapshot().activeTransfers());
      assertEquals(0, server.getMetricsSnapshot().queuedTasks());
    } finally {
      for (Socket socket : sockets) socket.close();
      server.close();
    }
  }

  private Path largeFile() throws IOException {
    Path file = root.resolve("large.bin");
    try (FileChannel out =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      out.position(16L * 1024 * 1024 - 1);
      out.write(ByteBuffer.wrap(new byte[] {1}));
    }
    return file;
  }

  private static Socket request(OperatorBoardServer server, String path, boolean slow)
      throws IOException {
    Socket socket = new Socket();
    if (slow) socket.setReceiveBufferSize(1024);
    socket.setSoTimeout(5000);
    socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
    socket
        .getOutputStream()
        .write(
            ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    return socket;
  }

  private static ThreadPoolExecutor executor(OperatorBoardServer server) throws Exception {
    Field field = OperatorBoardServer.class.getDeclaredField("executor");
    field.setAccessible(true);
    return (ThreadPoolExecutor) field.get(server);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("Synchronization condition not reached");
      Thread.sleep(1);
    }
  }

  private static String readHeaders(InputStream input) throws IOException {
    StringBuilder result = new StringBuilder();
    while (!result.toString().endsWith("\r\n\r\n")) {
      int next = input.read();
      if (next < 0 || result.length() > 4096)
        throw new IOException("Incomplete/large response headers");
      result.append((char) next);
    }
    return result.toString();
  }
}
