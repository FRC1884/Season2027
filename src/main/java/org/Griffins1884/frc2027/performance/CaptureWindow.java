package org.Griffins1884.frc2027.performance;

/** Monotonic recording budget only. Expiration is never a request to stop robot execution. */
final class CaptureWindow {
  private final long startedNanos;
  private final long durationNanos;

  CaptureWindow(long startedNanos, long durationNanos) {
    if (durationNanos <= 0) {
      throw new IllegalArgumentException("Recording duration must be positive");
    }
    this.startedNanos = startedNanos;
    this.durationNanos = durationNanos;
  }

  boolean expired(long nowNanos) {
    return nowNanos - startedNanos >= durationNanos;
  }
}
