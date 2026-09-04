package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBoardServerTest {
  @TempDir Path contentRoot;

  @Test
  void servesFrontendRoot() throws Exception {
    Files.writeString(contentRoot.resolve("index.html"), "<html>operator board</html>");

    try (OperatorBoardServer server = OperatorBoardServer.start(contentRoot, "127.0.0.1", 0)) {
      HttpResponse<String> response = get(server, "/");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("operator board"));
    }
  }

  @Test
  void returnsEmptyAutonomousManifest() throws Exception {
    try (OperatorBoardServer server = OperatorBoardServer.start(contentRoot, "127.0.0.1", 0)) {
      HttpResponse<String> response = get(server, "/planner-autos/index.json");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"autos\":[]"));
    }
  }

  private static HttpResponse<String> get(OperatorBoardServer server, String path)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
