package org.Griffins1884.frc2027.util;

/** A per-producer 10 Hz telemetry gate. The caller supplies its existing runtime-mode policy. */
public final class TelemetryCadence {
  private static final double PERIOD_SECONDS = 0.1;
  private static final double ROUNDING_TOLERANCE_SECONDS = 1e-9;
  private double lastPublicationSeconds = Double.NaN;
  private double lastCallSeconds = Double.NaN;
  private boolean previousFullRate;

  /**
   * Publishes initially, on mode changes or a clock rewind, and at 10 Hz otherwise. Full-rate
   * consumers publish on every call. Call once per acquisition cycle with a finite clock value.
   */
  public boolean shouldPublish(double nowSeconds, boolean fullRate) {
    boolean publish =
        Double.isNaN(lastPublicationSeconds)
            || fullRate
            || fullRate != previousFullRate
            || nowSeconds < lastCallSeconds
            || nowSeconds - lastPublicationSeconds >= PERIOD_SECONDS - ROUNDING_TOLERANCE_SECONDS;
    lastCallSeconds = nowSeconds;
    previousFullRate = fullRate;
    if (publish) {
      lastPublicationSeconds = nowSeconds;
    }
    return publish;
  }
}
