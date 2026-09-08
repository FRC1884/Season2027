package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAssetAccessTest {
  @TempDir Path root;

  @Test
  void opensRootAndNestedFilesWithoutBufferingBodies() throws Exception {
    Files.writeString(root.resolve("index.html"), "home");
    Files.createDirectories(root.resolve("a/b"));
    Files.writeString(root.resolve("a/b/module.js"), "export {};");
    StaticAssetAccess access = new StaticAssetAccess(root);
    try (var asset = access.inspect("/")) {
      assertEquals(Path.of("index.html"), asset.relativePath());
      assertEquals(4, asset.attributes().size());
      assertTrue(asset.unchanged());
    }
    try (var asset = access.inspect("/a/b/module.js");
        var body = asset.openBody()) {
      ByteBuffer bytes = ByteBuffer.allocate(10);
      assertEquals(10, body.read(bytes));
      assertArrayEquals(
          "export {};".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes.array());
    }
  }

  @Test
  void rejectsTraversalIncludingDecodedEncodedPaths() throws Exception {
    StaticAssetAccess access = new StaticAssetAccess(root);
    for (String path : new String[] {"/../secret", "/a/../../secret", "//outside", "relative"}) {
      assertThrows(AccessDeniedException.class, () -> access.inspect(path));
    }
    String decoded = URI.create("http://localhost/%2e%2e/secret").getPath();
    assertThrows(AccessDeniedException.class, () -> access.inspect(decoded));
  }

  @Test
  void rejectsFileDirectoryAndRootSymlinks() throws Exception {
    Path outside = Files.createTempDirectory(root, "outside");
    Files.writeString(outside.resolve("secret"), "private");
    Path serving = Files.createDirectory(root.resolve("serving"));
    Files.createSymbolicLink(serving.resolve("file"), outside.resolve("secret"));
    Files.createSymbolicLink(serving.resolve("directory"), outside);
    StaticAssetAccess access = new StaticAssetAccess(serving);
    assertThrows(IOException.class, () -> access.inspect("/file"));
    assertThrows(IOException.class, () -> access.inspect("/directory/secret"));
    Files.createSymbolicLink(root.resolve("linked-root"), serving);
    assertThrows(
        IOException.class,
        () -> new StaticAssetAccess(root.resolve("linked-root")).inspect("/file"));
  }

  @Test
  void missingRootAndDirectoriesDoNotRequireStartupFiles() throws Exception {
    StaticAssetAccess access = new StaticAssetAccess(root.resolve("not-yet-deployed"));
    assertThrows(NoSuchFileException.class, () -> access.inspect("/"));
    Files.createDirectory(root.resolve("folder"));
    assertThrows(NoSuchFileException.class, () -> new StaticAssetAccess(root).inspect("/folder"));
  }

  @Test
  void disappearanceAndSameSizeReplacementBeforeOpenFail() throws Exception {
    Path file = Files.writeString(root.resolve("index.html"), "old");
    StaticAssetAccess access = new StaticAssetAccess(root);
    try (var asset = access.inspect("/")) {
      Files.delete(file);
      assertFalse(asset.unchanged());
      assertThrows(IOException.class, asset::openBody);
    }
    Files.writeString(file, "old");
    try (var asset = access.inspect("/")) {
      var time = Files.getLastModifiedTime(file);
      Path replacement = Files.writeString(root.resolve("replacement"), "new");
      Files.setLastModifiedTime(replacement, time);
      Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      assertFalse(asset.unchanged());
      assertThrows(IOException.class, asset::openBody);
    }
  }

  @Test
  void directoryReplacementCannotRedirectTheOpenedBody() throws Exception {
    Path directory = Files.createDirectory(root.resolve("nested"));
    Files.writeString(directory.resolve("asset"), "safe");
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.writeString(outside.resolve("asset"), "evil");
    try (var asset = new StaticAssetAccess(root).inspect("/nested/asset")) {
      Files.move(directory, root.resolve("original"));
      Files.createSymbolicLink(directory, outside);
      boolean secure;
      try (var directoryStream = Files.newDirectoryStream(root)) {
        secure = directoryStream instanceof java.nio.file.SecureDirectoryStream<?>;
      }
      if (secure) {
        try (var body = asset.openBody()) {
          ByteBuffer bytes = ByteBuffer.allocate(4);
          body.read(bytes);
          assertArrayEquals(
              "safe".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes.array());
        }
      } else {
        assertThrows(AccessDeniedException.class, asset::openBody);
      }
    }
  }

  @Test
  void changesAfterBodyOpenAreDetectedWithoutChangingTheCapturedLength() throws Exception {
    Path file = Files.writeString(root.resolve("index.html"), "initial");
    try (var asset = new StaticAssetAccess(root).inspect("/");
        var body = asset.openBody()) {
      assertEquals(7, asset.attributes().size());
      Files.writeString(file, "short");
      assertFalse(asset.unchanged());
      assertEquals(7, asset.attributes().size());
      assertEquals(5, body.size());
    }
  }

  @Test
  void deeplyNestedInspectionAndFailuresDoNotRetainFileDescriptors() throws Exception {
    var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    org.junit.jupiter.api.Assumptions.assumeTrue(
        bean instanceof com.sun.management.UnixOperatingSystemMXBean);
    var unix = (com.sun.management.UnixOperatingSystemMXBean) bean;
    Path nested = root;
    for (int index = 0; index < 24; index++) {
      nested = Files.createDirectory(nested.resolve("level"));
    }
    Path file = Files.writeString(nested.resolve("asset"), "body");
    String request = "/" + root.relativize(file);
    StaticAssetAccess access = new StaticAssetAccess(root);
    try (var asset = access.inspect(request);
        var body = asset.openBody()) {
      assertEquals(4, body.size());
    }
    long before = unix.getOpenFileDescriptorCount();
    for (int index = 0; index < 100; index++) {
      try (var asset = access.inspect(request);
          var body = asset.openBody()) {
        // Only the final directory and the body may remain open, regardless of depth.
        assertTrue(unix.getOpenFileDescriptorCount() <= before + 3);
      }
      assertThrows(NoSuchFileException.class, () -> access.inspect(request + "missing"));
    }
    assertTrue(unix.getOpenFileDescriptorCount() <= before);
  }

  @Test
  void closedAssetCannotOpenAndClosesOutstandingBody() throws Exception {
    Files.writeString(root.resolve("index.html"), "body");
    var asset = new StaticAssetAccess(root).inspect("/");
    SeekableByteChannel body = asset.openBody();
    assertThrows(IOException.class, asset::openBody);
    asset.close();
    asset.close();
    assertFalse(body.isOpen());
    assertThrows(IOException.class, asset::openBody);
  }
}
