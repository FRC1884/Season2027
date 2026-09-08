package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBoardHttpTest {
  @TempDir Path root;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpResponse<byte[]> get(OperatorBoardServer server, String path, String tag)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
            .timeout(Duration.ofSeconds(5));
    if (tag != null) request.header("If-None-Match", tag);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void knownAssetsKeepTheirBytesMimeAndRootMapping() throws Exception {
    var files =
        Map.of(
            "index.html",
            "<html>héllo</html>",
            "index.js",
            "import './NT4.js';",
            "NT4.js",
            "export const value=1;",
            "index.css",
            "body{color:red}",
            "data.json",
            "{\"fresh\":true}");
    for (var item : files.entrySet())
      Files.writeString(root.resolve(item.getKey()), item.getValue());
    byte[] image = {(byte) 137, 80, 78, 71, 0, (byte) 255};
    Files.write(root.resolve("Other.png"), image);
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      for (var item : files.entrySet()) {
        var response = get(server, "/" + item.getKey(), null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(
            item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8), response.body());
        String type = response.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(
            type.contains(
                item.getKey().endsWith(".js")
                    ? "javascript"
                    : item.getKey().endsWith(".css")
                        ? "css"
                        : item.getKey().endsWith(".json") ? "json" : "html"));
      }
      assertArrayEquals(image, get(server, "/Other.png", null).body());
      assertEquals(
          "image/png",
          get(server, "/Other.png", null).headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(get(server, "/index.html", null).body(), get(server, "/", null).body());
    }
  }

  @Test
  void conditionalRequestsDoNotOpenOrReadTheBodyAndHaveNoPayload() throws Exception {
    Files.writeString(root.resolve("index.js"), "export const x=1;");
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      var first = get(server, "/index.js", null);
      String tag = first.headers().firstValue("ETag").orElseThrow();
      assertEquals("no-cache", first.headers().firstValue("Cache-Control").orElseThrow());
      var before = server.getMetricsSnapshot();
      for (String header : new String[] {tag, tag.substring(2), "\"other,tag\", " + tag, "*"}) {
        var response = get(server, "/index.js?different-query=1", header);
        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals(tag, response.headers().firstValue("ETag").orElseThrow());
        assertFalse(response.headers().firstValue("Transfer-Encoding").isPresent());
      }
      var after = server.getMetricsSnapshot();
      assertEquals(before.fileBodyOpens(), after.fileBodyOpens());
      assertEquals(before.fileReadOperations(), after.fileReadOperations());
      assertEquals(before.fileBytesRead(), after.fileBytesRead());
      assertEquals(4, after.notModifiedResponses());
      assertEquals(200, get(server, "/index.js", tag + ", invalid").statusCode());
    }
  }

  @Test
  void changedSameSizeContentAndRestartInvalidateOldTags() throws Exception {
    Path file = root.resolve("index.js");
    Files.writeString(file, "first");
    String oldTag;
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      oldTag = get(server, "/index.js", null).headers().firstValue("ETag").orElseThrow();
      FileTime before = Files.getLastModifiedTime(file);
      Files.writeString(file, "other");
      Files.setLastModifiedTime(file, FileTime.fromMillis(before.toMillis() + 2000));
      var changed = get(server, "/index.js", oldTag);
      assertEquals(200, changed.statusCode());
      assertArrayEquals("other".getBytes(), changed.body());
      oldTag = changed.headers().firstValue("ETag").orElseThrow();
    }
    FileTime preserved = Files.getLastModifiedTime(file);
    Files.writeString(file, "third");
    Files.setLastModifiedTime(file, preserved);
    try (var restarted = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      var changed = get(restarted, "/index.js", oldTag);
      assertEquals(200, changed.statusCode());
      assertArrayEquals("third".getBytes(), changed.body());
      assertNotEquals(oldTag, changed.headers().firstValue("ETag").orElseThrow());
    }
  }

  @Test
  void emptyMissingMethodsAndRuntimeRoutesPreserveContracts() throws Exception {
    Files.write(root.resolve("index.html"), new byte[0]);
    Files.writeString(root.resolve("rebuilt-spots.json"), "{}");
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      var empty = get(server, "/", null);
      assertEquals(200, empty.statusCode());
      assertEquals(0, empty.body().length);
      assertEquals("0", empty.headers().firstValue("Content-Length").orElseThrow());
      assertFalse(empty.headers().firstValue("Transfer-Encoding").isPresent());
      for (String path :
          new String[] {"/missing", "/api/diagnostics/latest", "/planner-autos/missing.json"}) {
        var response = get(server, path, null);
        assertEquals(404, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.headers().firstValue("ETag").isPresent());
      }
      for (String path :
          new String[] {
            "/rebuilt-spots.json",
            "/planner-autos/index.json?ts=1",
            "/planner-autos/index.json/suffix"
          }) {
        var response = get(server, path, "*");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.headers().firstValue("ETag").isPresent());
      }
      long opens = server.getMetricsSnapshot().fileBodyOpens();
      for (String method : new String[] {"POST", "HEAD", "PUT"}) {
        var request =
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/index.html"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
      }
      assertEquals(opens, server.getMetricsSnapshot().fileBodyOpens());
    }
  }

  @Test
  void pathQueriesAndMissingPathsDoNotGrowValidatorStateOrEscapeRoot() throws Exception {
    Files.writeString(root.resolve("index.js"), "safe");
    Path outside = Files.createTempFile("outside-dashboard-", ".txt");
    Files.writeString(outside, "private");
    try (var server = OperatorBoardServer.start(root, "127.0.0.1", 0)) {
      for (int i = 0; i < 100; i++) {
        assertEquals(200, get(server, "/index.js?q=" + i, null).statusCode());
        assertEquals(404, get(server, "/missing-" + i, null).statusCode());
      }
      for (String path :
          new String[] {"/%2e%2e/secret", "/..%2fsecret", "//etc/passwd", "/default-data/"})
        assertEquals(404, get(server, path, null).statusCode());
      Files.createSymbolicLink(root.resolve("escape.js"), outside);
      assertTrue(get(server, "/escape.js", null).statusCode() >= 400);
      assertEquals(0, server.getMetricsSnapshot().validatorCacheEntries());
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
