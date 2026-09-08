package org.Griffins1884.frc2027.subsystems.swerve;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One serial worker and at most one pending immutable request per drivetrain module. */
public final class ModuleConfigurationWorker implements AutoCloseable {
  @FunctionalInterface
  interface Backend {
    /** Apply both motors. Empty string means every required operation succeeded. */
    String apply(ModuleConfiguration gains, boolean brake) throws Exception;
  }

  public record Status(
      long desiredRevision,
      long appliedRevision,
      boolean inFlight,
      boolean failed,
      boolean inhibited,
      long attempts,
      long completions,
      double lastDurationMs,
      String error,
      long failureCount,
      long lastFailedRevision,
      String lastFailureError) {
    public static final Status READY = new Status(0, 0, false, false, false, 0, 0, 0, "", 0, 0, "");
  }

  private record Request(
      long revision, ModuleConfiguration gains, boolean brake, int attemptsUsed) {}

  private final Object monitor = new Object();
  private final List<Handle> handles = new ArrayList<>(4);
  private final BooleanSupplier disabled;
  private final Thread thread;
  private volatile boolean closed;

  public ModuleConfigurationWorker(BooleanSupplier disabled) {
    this.disabled = disabled;
    thread = new Thread(this::run, "SwerveConfiguration");
    thread.setDaemon(true);
    thread.start();
  }

  Handle register(Backend backend) {
    synchronized (monitor) {
      if (closed || handles.size() == 4)
        throw new IllegalStateException("Configuration worker capacity/closed");
      Handle handle = new Handle(backend);
      handles.add(handle);
      return handle;
    }
  }

  public final class Handle {
    private final Backend backend;
    private Request desired;
    private Request pending;
    private boolean initialized;
    private volatile Status status =
        new Status(0, -1, false, false, true, 0, 0, 0, "Not initialized", 0, 0, "");

    private Handle(Backend backend) {
      this.backend = backend;
    }

    /** Controlled startup only, before the module is exposed to normal outputs. */
    public void initialize(ModuleConfiguration gains) {
      Request initial;
      synchronized (monitor) {
        if (desired != null || closed)
          throw new IllegalStateException("Already initialized/closed");
        desired = initial = new Request(1, gains, true, 0);
        status = new Status(1, -1, true, false, true, 0, 0, 0, "", 0, 0, "");
      }
      apply(this, initial, true);
      synchronized (monitor) {
        initialized = true;
      }
      updateState(disabled.getAsBoolean());
    }

    public boolean request(ModuleConfiguration gains) {
      synchronized (monitor) {
        if (!initialized || closed || !disabled.getAsBoolean()) return false;
        enqueue(gains, desired.brake());
        return true;
      }
    }

    public void setBrakeMode(boolean brake) {
      synchronized (monitor) {
        if (!initialized || closed) return;
        enqueue(desired.gains(), brake);
      }
    }

    private void enqueue(ModuleConfiguration gains, boolean brake) {
      if (desired.gains().equals(gains) && desired.brake() == brake) return;
      desired = pending = new Request(desired.revision() + 1, gains, brake, 0);
      Status s = status;
      status =
          new Status(
              desired.revision(),
              s.appliedRevision(),
              s.inFlight(),
              false,
              s.inhibited() || !disabled.getAsBoolean(),
              s.attempts(),
              s.completions(),
              s.lastDurationMs(),
              "",
              s.failureCount(),
              s.lastFailedRevision(),
              s.lastFailureError());
      monitor.notifyAll();
    }

    /** Main-loop acknowledgement: completion while enabled never silently restores motion. */
    public void updateState(boolean isDisabled) {
      synchronized (monitor) {
        isDisabled = isDisabled && disabled.getAsBoolean();
        Status s = status;
        boolean complete =
            !s.inFlight() && !s.failed() && s.desiredRevision() == s.appliedRevision();
        boolean inhibit =
            isDisabled && complete ? false : s.inhibited() || (!isDisabled && !complete);
        if (inhibit != s.inhibited()) {
          status =
              new Status(
                  s.desiredRevision(),
                  s.appliedRevision(),
                  s.inFlight(),
                  s.failed(),
                  inhibit,
                  s.attempts(),
                  s.completions(),
                  s.lastDurationMs(),
                  s.error(),
                  s.failureCount(),
                  s.lastFailedRevision(),
                  s.lastFailureError());
        }
        if (isDisabled && pending != null) monitor.notifyAll();
      }
    }

    public boolean isReady() {
      Status s = status;
      return !closed
          && !s.inhibited()
          && !s.inFlight()
          && !s.failed()
          && s.desiredRevision() == s.appliedRevision();
    }

    public Status status() {
      return status;
    }
  }

  private void run() {
    while (true) {
      Handle selected = null;
      Request request = null;
      synchronized (monitor) {
        while (!closed && selected == null) {
          if (disabled.getAsBoolean()) {
            for (Handle handle : handles) {
              if (handle.initialized && handle.pending != null) {
                selected = handle;
                request = handle.pending;
                handle.pending = null;
                Status s = handle.status;
                handle.status =
                    new Status(
                        s.desiredRevision(),
                        s.appliedRevision(),
                        true,
                        false,
                        s.inhibited(),
                        s.attempts(),
                        s.completions(),
                        s.lastDurationMs(),
                        "",
                        s.failureCount(),
                        s.lastFailedRevision(),
                        s.lastFailureError());
                break;
              }
            }
          }
          if (selected == null) {
            try {
              monitor.wait();
            } catch (InterruptedException e) {
              if (closed) return;
            }
          }
        }
        if (closed) return;
      }
      apply(selected, request, false);
    }
  }

  private void apply(Handle handle, Request request, boolean startup) {
    long start = System.nanoTime();
    String error = "Configuration deferred: robot enabled";
    int attempts = 0;
    int failedAttempts = 0;
    String lastFailure = "";
    boolean success = false;
    while (attempts + request.attemptsUsed() < 5
        && !closed
        && (startup || disabled.getAsBoolean())) {
      attempts++;
      try {
        error = handle.backend.apply(request.gains(), request.brake());
        success = error.isEmpty();
      } catch (Exception exception) {
        error = exception.toString();
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      }
      if (!success) {
        failedAttempts++;
        lastFailure = error;
      }
      if (success || Thread.currentThread().isInterrupted()) break;
    }
    synchronized (monitor) {
      Status s = handle.status;
      boolean enabled = !disabled.getAsBoolean();
      // A completion belongs only to its own revision, even when a newer request is pending.
      handle.status =
          new Status(
              s.desiredRevision(),
              success ? request.revision() : s.appliedRevision(),
              false,
              !success,
              s.inhibited() || enabled,
              s.attempts() + attempts,
              s.completions() + 1,
              (System.nanoTime() - start) / 1e6,
              success ? "" : error,
              s.failureCount() + failedAttempts,
              failedAttempts > 0 ? request.revision() : s.lastFailedRevision(),
              failedAttempts > 0 ? lastFailure : s.lastFailureError());
      // An enable transition defers this exact transaction until another disabled acknowledgement.
      if (!success && enabled && handle.pending == null && attempts + request.attemptsUsed() < 5) {
        handle.pending =
            new Request(
                request.revision(),
                request.gains(),
                request.brake(),
                request.attemptsUsed() + attempts);
      }
      monitor.notifyAll();
    }
  }

  // Package-private deterministic test barrier; never used by robot periodic/control code.
  void awaitIdleForTest() {
    synchronized (monitor) {
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (handles.stream()
          .anyMatch(h -> h.status.inFlight() || (disabled.getAsBoolean() && h.pending != null))) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new AssertionError("Configuration did not become idle");
        try {
          monitor.wait(Math.max(1, remaining / 1_000_000));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    }
  }

  boolean isAliveForTest() {
    return thread.isAlive();
  }

  /** Lifecycle only: bounded Phoenix operations finish before devices are closed. */
  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      monitor.notifyAll();
    }
    thread.interrupt();
    boolean interrupted = false;
    while (thread.isAlive()) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
