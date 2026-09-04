package org.Griffins1884.frc2027.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Filesystem;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.Griffins1884.frc2027.Config;

/**
 * Serves the inherited operator-board frontend without retaining its prior-season mechanism
 * backend.
 *
 * <p>The frontend's NT4 client continues to connect directly to NetworkTables. Autonomous library
 * requests return an empty manifest until new 2027 routines are authored.
 */
public final class OperatorBoardServer implements AutoCloseable {
  private static final String EMPTY_AUTO_MANIFEST =
      "{\"version\":\"2027.0\",\"generator\":\"Season2027\",\"autos\":[]}";

  private final Path contentRoot;
  private final HttpServer server;
  private final ExecutorService executor;

  private OperatorBoardServer(Path contentRoot, String bindAddress, int port) throws IOException {
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
    this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
    this.executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/planner-autos/index.json",
        exchange -> {
          if (!requireMethod(exchange, "GET")) {
            return;
          }
          sendJson(exchange, 200, EMPTY_AUTO_MANIFEST);
        });
    server.createContext(
        "/planner-autos/",
        exchange -> {
          if (!requireMethod(exchange, "GET")) {
            return;
          }
          sendText(exchange, 404, "No 2027 autonomous routines are deployed.");
        });
    server.createContext("/", new StaticFileHandler());
  }

  /** Starts the deploy-directory server when the web UI is enabled. */
  public static OperatorBoardServer startDefault() {
    if (!Config.WebUIConfig.ENABLED) {
      return null;
    }
    try {
      return start(
          Filesystem.getDeployDirectory().toPath().resolve("operatorboard"),
          Config.WebUIConfig.BIND_ADDRESS,
          Config.WebUIConfig.PORT);
    } catch (IOException ex) {
      DriverStation.reportError("Failed to start operator board web server", ex.getStackTrace());
      return null;
    }
  }

  /** Starts a server for the supplied content root. A port of zero selects an ephemeral port. */
  public static OperatorBoardServer start(Path contentRoot, String bindAddress, int port)
      throws IOException {
    OperatorBoardServer result = new OperatorBoardServer(contentRoot, bindAddress, port);
    result.server.start();
    return result;
  }

  public int getPort() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private final class StaticFileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!requireMethod(exchange, "GET")) {
        return;
      }
      String requestPath = exchange.getRequestURI().getPath();
      String relativePath = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
      Path target = contentRoot.resolve(relativePath).normalize();
      if (!target.startsWith(contentRoot) || !Files.isRegularFile(target)) {
        sendText(exchange, 404, "Not found");
        return;
      }

      byte[] body = Files.readAllBytes(target);
      Headers headers = exchange.getResponseHeaders();
      headers.set("Content-Type", contentType(target));
      headers.set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    }
  }

  private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
    if (method.equalsIgnoreCase(exchange.getRequestMethod())) {
      return true;
    }
    sendText(exchange, 405, "Method not allowed");
    return false;
  }

  private static String contentType(Path file) throws IOException {
    String detected = Files.probeContentType(file);
    if (detected != null) {
      return detected;
    }
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".js")) {
      return "application/javascript";
    }
    if (name.endsWith(".css")) {
      return "text/css";
    }
    if (name.endsWith(".json")) {
      return "application/json";
    }
    if (name.endsWith(".png")) {
      return "image/png";
    }
    if (name.endsWith(".html")) {
      return "text/html; charset=utf-8";
    }
    return "application/octet-stream";
  }

  private static void sendJson(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    send(exchange, statusCode, "application/json; charset=utf-8", body);
  }

  private static void sendText(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    send(exchange, statusCode, "text/plain; charset=utf-8", body);
  }

  private static void send(HttpExchange exchange, int statusCode, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
