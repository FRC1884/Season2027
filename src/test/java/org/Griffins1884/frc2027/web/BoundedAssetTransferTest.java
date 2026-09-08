package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedAssetTransferTest {
  @Test
  void generatedLargeBodyUsesOnlyTheSuppliedSmallBuffer() throws Exception {
    long size = 8L * 1024 * 1024 + 7;
    byte[] buffer = new byte[16 * 1024];
    AtomicLong generated = new AtomicLong(), received = new AtomicLong();
    AtomicInteger maxRead = new AtomicInteger();
    ReadableByteChannel source =
        new ReadableByteChannel() {
          public boolean isOpen() {
            return true;
          }

          public void close() {}

          public int read(ByteBuffer target) {
            int n = (int) Math.min(target.remaining(), size - generated.get());
            maxRead.accumulateAndGet(target.remaining(), Math::max);
            for (int i = 0; i < n; i++) target.put((byte) (generated.getAndIncrement() % 251));
            return n == 0 ? -1 : n;
          }
        };
    OutputStream sink =
        new OutputStream() {
          public void write(int b) {
            fail("Unexpected single-byte output");
          }

          public void write(byte[] b, int off, int len) {
            assertSame(buffer, b);
            for (int i = 0; i < len; i++)
              assertEquals((byte) (received.getAndIncrement() % 251), b[off + i]);
          }
        };
    BoundedAssetTransfer.copy(source, sink, size, buffer, () -> true, () -> {}, n -> {}, n -> {});
    assertEquals(size, received.get());
    assertEquals(buffer.length, maxRead.get());
  }

  @Test
  void changedSourceWithholdsTheFinalChunkAndDoesNotReportCompleteBody() {
    AtomicLong sent = new AtomicLong();
    assertThrows(
        IOException.class,
        () ->
            BoundedAssetTransfer.copy(
                java.nio.channels.Channels.newChannel(new ByteArrayInputStream(new byte[20])),
                OutputStream.nullOutputStream(),
                20,
                new byte[8],
                () -> false,
                () -> {},
                n -> {},
                sent::addAndGet));
    assertEquals(16, sent.get());
  }

  @Test
  void prematureEofAndOutputFailurePropagate() {
    assertThrows(
        EOFException.class,
        () ->
            BoundedAssetTransfer.copy(
                java.nio.channels.Channels.newChannel(new ByteArrayInputStream(new byte[3])),
                OutputStream.nullOutputStream(),
                5,
                new byte[8],
                () -> true,
                () -> {},
                n -> {},
                n -> {}));
    assertThrows(
        IOException.class,
        () ->
            BoundedAssetTransfer.copy(
                java.nio.channels.Channels.newChannel(new ByteArrayInputStream(new byte[8])),
                new OutputStream() {
                  public void write(int b) throws IOException {
                    throw new IOException("Disconnected");
                  }
                },
                8,
                new byte[8],
                () -> true,
                () -> {},
                n -> {},
                n -> {}));
  }

  @Test
  void emptySourceDoesNotReadOrWriteAndStillChecksFreshness() throws Exception {
    BoundedAssetTransfer.copy(
        null,
        null,
        0,
        new byte[8],
        () -> true,
        () -> fail("read"),
        n -> fail("read"),
        n -> fail("write"));
    assertThrows(
        IOException.class,
        () ->
            BoundedAssetTransfer.copy(
                null, null, 0, new byte[8], () -> false, () -> {}, n -> {}, n -> {}));
  }
}
