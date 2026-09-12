package org.Griffins1884.frc2027.web;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded access to an asset tree that remains immutable until the server is stopped.
 *
 * <p>Uses descriptor-relative no-follow access where the filesystem supports it. The portable
 * backend checks every component and canonical containment before and after opening a body, but
 * Java 17 cannot make those checks atomic with concurrent ancestor-directory renames. Such live
 * mutations are unsupported: stop the server, replace assets, then restart.
 */
final class StaticAssetAccess {
  private final Path contentRoot;
  private volatile Path anchoredRoot;

  StaticAssetAccess(Path contentRoot) {
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
  }

  /** Inspects a decoded URI path without opening or reading the response body. */
  Asset inspect(String decodedRequestPath) throws IOException {
    Path relative = relativePath(decodedRequestPath);
    Path anchoredRoot = anchoredRoot();
    SecureDirectoryStream<Path> directory = openSecureRoot(anchoredRoot.getRoot());
    if (directory == null) {
      BasicFileAttributes attributes = readPortableAttributes(anchoredRoot, relative);
      return new Asset(relative, null, anchoredRoot, attributes);
    }
    try {
      for (Path component : anchoredRoot) {
        directory = descend(directory, component);
      }
      for (int index = 0; index < relative.getNameCount() - 1; index++) {
        directory = descend(directory, relative.getName(index));
      }
      Path name = relative.getFileName();
      BasicFileAttributes attributes = readAttributes(directory, name);
      if (!attributes.isRegularFile()) {
        throw new NoSuchFileException(relative.toString());
      }
      Asset result = new Asset(relative, directory, anchoredRoot, attributes);
      directory = null;
      return result;
    } finally {
      if (directory != null) {
        directory.close();
      }
    }
  }

  private Path anchoredRoot() throws IOException {
    Path result = anchoredRoot;
    if (result == null) {
      // The configured parent is trusted for the server lifetime and may use platform aliases
      // such as /tmp on macOS. Cache only its canonical path, never file contents or descriptors.
      // Resolve lazily so an absent deploy parent does not prevent server startup; failures retry.
      Path parent = contentRoot.getParent();
      result =
          parent == null ? contentRoot : parent.toRealPath().resolve(contentRoot.getFileName());
      anchoredRoot = result;
    }
    return result;
  }

  private static Path relativePath(String decoded) throws IOException {
    if (!decoded.startsWith("/") || decoded.startsWith("//")) {
      throw new AccessDeniedException(decoded);
    }
    try {
      Path result = Path.of(decoded.equals("/") ? "index.html" : decoded.substring(1));
      if (result.isAbsolute() || result.toString().isEmpty()) {
        throw new AccessDeniedException(decoded);
      }
      for (Path component : result) {
        if (component.toString().equals("..")) {
          throw new AccessDeniedException(decoded);
        }
      }
      return result.normalize();
    } catch (InvalidPathException ex) {
      throw new AccessDeniedException(decoded);
    }
  }

  private static SecureDirectoryStream<Path> openSecureRoot(Path path) throws IOException {
    DirectoryStream<Path> opened = Files.newDirectoryStream(path);
    if (opened instanceof SecureDirectoryStream<Path> secure) {
      opened.close();
      return secure;
    }
    opened.close();
    return null;
  }

  /** Never retains more than the current and next directory handles during traversal. */
  private static SecureDirectoryStream<Path> descend(SecureDirectoryStream<Path> parent, Path name)
      throws IOException {
    SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
    try {
      parent.close();
      return child;
    } catch (IOException ex) {
      child.close();
      throw ex;
    }
  }

  private static BasicFileAttributes readAttributes(
      SecureDirectoryStream<Path> directory, Path name) throws IOException {
    BasicFileAttributeView view =
        directory.getFileAttributeView(
            name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("The asset filesystem does not expose basic file attributes");
    }
    return view.readAttributes();
  }

  private static BasicFileAttributes readPortableAttributes(Path root, Path relative)
      throws IOException {
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root)) {
      throw new AccessDeniedException(relative.toString());
    }
    BasicFileAttributes attributes =
        Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()) {
      throw new AccessDeniedException(relative.toString());
    }
    if (!attributes.isDirectory()) {
      throw new NoSuchFileException(relative.toString());
    }
    Path current = root;
    for (Path component : relative) {
      current = current.resolve(component);
      attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()) {
        throw new AccessDeniedException(relative.toString());
      }
      if (!current.equals(target) && !attributes.isDirectory()) {
        throw new NoSuchFileException(relative.toString());
      }
    }
    if (!attributes.isRegularFile()) {
      throw new NoSuchFileException(relative.toString());
    }
    // Validate the canonical paths too; the target and root must still be the checked tree.
    if (!root.toRealPath().equals(root) || !target.toRealPath().equals(target)) {
      throw new AccessDeniedException(relative.toString());
    }
    return attributes;
  }

  /**
   * Owns at most one directory stream and one body channel; never allocates body storage. A JDK17
   * Unix secure directory stream holds two OS descriptors.
   */
  static final class Asset implements AutoCloseable {
    private final Path relativePath;
    private final SecureDirectoryStream<Path> directory;
    private final Path root;
    private final BasicFileAttributes attributes;
    private SeekableByteChannel body;
    private boolean closed;

    private Asset(
        Path relativePath,
        SecureDirectoryStream<Path> directory,
        Path root,
        BasicFileAttributes attributes) {
      this.relativePath = relativePath;
      this.directory = directory;
      this.root = root;
      this.attributes = attributes;
    }

    Path relativePath() {
      return relativePath;
    }

    BasicFileAttributes attributes() {
      return attributes;
    }

    SeekableByteChannel openBody() throws IOException {
      if (closed || body != null) {
        throw new IOException("Asset is closed or its body was already opened");
      }
      if (!unchanged()) {
        throw new IOException("Asset changed before transfer");
      }
      SeekableByteChannel opened =
          directory == null
              ? Files.newByteChannel(
                  root.resolve(relativePath),
                  Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
              : directory.newByteChannel(
                  relativePath.getFileName(),
                  Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
      try {
        if (!unchanged() || opened.size() != attributes.size()) {
          throw new IOException("Asset changed while opening transfer");
        }
        body = opened;
        return body;
      } finally {
        if (body == null) {
          opened.close();
        }
      }
    }

    boolean unchanged() throws IOException {
      if (closed) {
        return false;
      }
      try {
        BasicFileAttributes current =
            directory == null
                ? readPortableAttributes(root, relativePath)
                : readAttributes(directory, relativePath.getFileName());
        return current.isRegularFile()
            && attributes.fileKey() != null
            && Objects.equals(attributes.fileKey(), current.fileKey())
            && attributes.size() == current.size()
            && attributes.lastModifiedTime().equals(current.lastModifiedTime())
            && attributes.creationTime().equals(current.creationTime());
      } catch (NoSuchFileException ex) {
        return false;
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        if (body != null) {
          body.close();
        }
      } finally {
        if (directory != null) {
          directory.close();
        }
      }
    }
  }
}
