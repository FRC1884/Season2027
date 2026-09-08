package org.Griffins1884.frc2027.performance;

/** Fixed primitive storage. A single producer never waits for file I/O or a slow consumer. */
public final class LoopSampleQueue {
  private final long[][] values;
  private final String[] phases;
  private volatile long written;
  private volatile long read;
  private volatile long dropped;

  public LoopSampleQueue(int capacity) {
    if (capacity < 1 || capacity > 4096) {
      throw new IllegalArgumentException("Sample capacity must be 1..4096");
    }
    values = new long[capacity][10];
    phases = new String[capacity];
  }

  public boolean offer(LoopSample sample) {
    if (written - read == values.length) {
      dropped++;
      return false;
    }
    int slot = (int) (written % values.length);
    long[] v = values[slot];
    v[0] = sample.cycle();
    v[1] = sample.startNanos();
    v[2] = sample.endNanos();
    v[3] = sample.originalDueUs();
    v[4] = sample.effectiveDueUs();
    v[5] = sample.startUs();
    v[6] = sample.endUs();
    v[7] = sample.periodUs();
    v[8] = sample.intervalNanos();
    v[9] = sample.skippedReleases();
    phases[slot] = sample.phase();
    written++;
    return true;
  }

  public LoopSample poll() {
    if (read == written) {
      return null;
    }
    int slot = (int) (read % values.length);
    long[] v = values[slot];
    LoopSample result =
        new LoopSample(v[0], phases[slot], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    phases[slot] = null;
    read++;
    return result;
  }

  public long dropped() {
    return dropped;
  }

  public long size() {
    return written - read;
  }
}
