package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.Griffins1884.frc2027.subsystems.swerve.SwerveSubsystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopEvidenceWriterTest {
  @TempDir Path output;

  @Test
  void completeCaptureClosesWriterAndRetainsEveryAcceptedSample() throws Exception {
    var writer = new DesktopMatchMain.FrameWriter(output);
    var drive =
        new SwerveSubsystem.PerformanceSnapshot(
            true, true, 5, 5, 0, 0, 0, 1, 1, .02, 100, 10, 20, 30, 40, 50, 60);
    for (int cycle = 0; cycle < 20; cycle++) {
      var sample =
          new LoopSample(
              cycle,
              "teleop",
              cycle * 20_000_000L,
              cycle * 20_000_000L + 1_000_000,
              cycle * 20_000L,
              cycle * 20_000L,
              cycle * 20_000L,
              cycle * 20_000L + 1000,
              20_000,
              20_000_000,
              0);
      writer.offer(new DesktopMatchMain.Frame(sample, drive, 200, null, 300, 400));
    }
    writer.finish();
    writer.finish();
    assertFalse(writer.thread.isAlive());
    assertEquals(21, Files.readAllLines(output.resolve("loop-samples.csv")).size());
    assertEquals(201, Files.readAllLines(output.resolve("subsystem-timings.csv")).size());
    assertEquals(1, Files.readAllLines(output.resolve("resource-samples.csv")).size());
  }

  @Test
  void ioFailureIsReportedAndDoesNotLeaveWriterRunning() throws Exception {
    Path file = output.resolve("not-a-directory");
    Files.writeString(file, "owned fixture");
    var writer = new DesktopMatchMain.FrameWriter(file);
    assertThrows(IllegalStateException.class, writer::finish);
    assertFalse(writer.thread.isAlive());
  }
}
