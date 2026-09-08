package org.Griffins1884.frc2027.performance;

/**
 * Completed outer cycle. HAL times are microseconds; elapsed profiling uses monotonic nanoseconds.
 */
public record LoopSample(
    long cycle,
    String phase,
    long startNanos,
    long endNanos,
    long originalDueUs,
    long effectiveDueUs,
    long startUs,
    long endUs,
    long periodUs,
    long intervalNanos,
    long skippedReleases) {
  public double executionMs() {
    return (endNanos - startNanos) / 1e6;
  }

  public double latenessMs() {
    return Math.max(0, startUs - originalDueUs) / 1000.0;
  }

  public boolean deadlineMiss() {
    return endUs > originalDueUs + periodUs;
  }

  public boolean executionOverrun() {
    return endNanos - startNanos > periodUs * 1000;
  }

  public double headroomMs() {
    return (originalDueUs + periodUs - endUs) / 1000.0;
  }
}
