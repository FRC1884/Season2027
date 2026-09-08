package org.Griffins1884.frc2027.web;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.function.LongConsumer;

/** Fixed-length transfer with a withheld final chunk until the source is revalidated. */
final class BoundedAssetTransfer {
  @FunctionalInterface
  interface Unchanged {
    boolean get() throws IOException;
  }

  private BoundedAssetTransfer() {}

  static void copy(
      ReadableByteChannel input,
      OutputStream output,
      long length,
      byte[] buffer,
      Unchanged unchanged,
      Runnable readOperation,
      LongConsumer readBytes,
      LongConsumer sentBytes)
      throws IOException {
    if (length < 0 || buffer.length == 0)
      throw new IllegalArgumentException("Invalid transfer bounds");
    long remaining = length;
    ByteBuffer bytes = ByteBuffer.wrap(buffer);
    while (remaining > 0) {
      int count = (int) Math.min(remaining, buffer.length);
      bytes.clear().limit(count);
      while (bytes.hasRemaining()) {
        readOperation.run();
        int read = input.read(bytes);
        if (read < 0) throw new EOFException("Asset shortened during response");
        if (read == 0) throw new IOException("Asset read made no progress");
        readBytes.accept(read);
      }
      if (remaining == count && !unchanged.get())
        throw new IOException("Asset changed during response");
      output.write(buffer, 0, count);
      sentBytes.accept(count);
      remaining -= count;
    }
    if (length == 0 && !unchanged.get()) throw new IOException("Empty asset changed");
  }
}
