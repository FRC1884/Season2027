package org.Griffins1884.frc2027.subsystems.pincer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.wpi.first.wpilibj2.command.Command;
import org.junit.jupiter.api.Test;

class PincerSubsystemTest {
  @Test
  void grabClampsToConfiguredMaxOutput() {
    FakePincerMotorIO leftJaw = new FakePincerMotorIO();
    FakePincerMotorIO rightJaw = new FakePincerMotorIO();
    PincerSubsystem subsystem =
        new PincerSubsystem(leftJaw, rightJaw, new PincerConfig(0.35, false, false));

    subsystem.grab(0.8);

    assertEquals(0.35, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.35, rightJaw.lastSetOutput, 1.0e-9);
  }

  @Test
  void releaseAppliesLogicalJawInversion() {
    FakePincerMotorIO leftJaw = new FakePincerMotorIO();
    FakePincerMotorIO rightJaw = new FakePincerMotorIO();
    PincerSubsystem subsystem =
        new PincerSubsystem(leftJaw, rightJaw, new PincerConfig(0.5, false, true));

    subsystem.release(0.4);

    assertEquals(-0.4, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.4, rightJaw.lastSetOutput, 1.0e-9);
  }

  @Test
  void grabCommandStopsMotorsWhenEnded() {
    FakePincerMotorIO leftJaw = new FakePincerMotorIO();
    FakePincerMotorIO rightJaw = new FakePincerMotorIO();
    PincerSubsystem subsystem =
        new PincerSubsystem(leftJaw, rightJaw, new PincerConfig(0.4, false, false));
    Command command = subsystem.grabCommand(0.3);

    command.initialize();
    command.end(false);

    assertEquals(0.3, leftJaw.lastSetOutputBeforeStop, 1.0e-9);
    assertEquals(0.3, rightJaw.lastSetOutputBeforeStop, 1.0e-9);
    assertEquals(0.0, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.0, rightJaw.lastSetOutput, 1.0e-9);
    assertEquals(1, leftJaw.stopCalls);
    assertEquals(1, rightJaw.stopCalls);
  }

  @Test
  void releaseCommandStopsMotorsWhenInterrupted() {
    FakePincerMotorIO leftJaw = new FakePincerMotorIO();
    FakePincerMotorIO rightJaw = new FakePincerMotorIO();
    PincerSubsystem subsystem =
        new PincerSubsystem(leftJaw, rightJaw, new PincerConfig(0.4, false, false));
    Command command = subsystem.releaseCommand(0.25);

    command.initialize();
    command.end(true);

    assertEquals(-0.25, leftJaw.lastSetOutputBeforeStop, 1.0e-9);
    assertEquals(-0.25, rightJaw.lastSetOutputBeforeStop, 1.0e-9);
    assertEquals(0.0, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.0, rightJaw.lastSetOutput, 1.0e-9);
    assertEquals(1, leftJaw.stopCalls);
    assertEquals(1, rightJaw.stopCalls);
  }

  @Test
  void configRejectsUnsafeMaxOutput() {
    assertThrows(IllegalArgumentException.class, () -> new PincerConfig(0.0, false, false));
    assertThrows(IllegalArgumentException.class, () -> new PincerConfig(1.1, false, false));
    assertThrows(IllegalArgumentException.class, () -> new PincerConfig(Double.NaN, false, false));
  }

  @Test
  void nonFiniteRequestedOutputFailsClosed() {
    FakePincerMotorIO leftJaw = new FakePincerMotorIO();
    FakePincerMotorIO rightJaw = new FakePincerMotorIO();
    PincerSubsystem subsystem =
        new PincerSubsystem(leftJaw, rightJaw, new PincerConfig(0.4, false, false));

    subsystem.grab(Double.NaN);
    assertEquals(0.0, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.0, rightJaw.lastSetOutput, 1.0e-9);
    assertEquals(1, leftJaw.stopCalls);
    assertEquals(1, rightJaw.stopCalls);

    subsystem.release(Double.POSITIVE_INFINITY);
    assertEquals(0.0, leftJaw.lastSetOutput, 1.0e-9);
    assertEquals(0.0, rightJaw.lastSetOutput, 1.0e-9);
    assertEquals(2, leftJaw.stopCalls);
    assertEquals(2, rightJaw.stopCalls);
  }

  private static final class FakePincerMotorIO implements PincerMotorIO {
    private double lastSetOutput = Double.NaN;
    private double lastSetOutputBeforeStop = Double.NaN;
    private int stopCalls;

    @Override
    public void set(double output) {
      lastSetOutput = output;
    }

    @Override
    public void stop() {
      lastSetOutputBeforeStop = lastSetOutput;
      lastSetOutput = 0.0;
      stopCalls++;
    }
  }
}
