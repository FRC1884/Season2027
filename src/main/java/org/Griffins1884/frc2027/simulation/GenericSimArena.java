package org.Griffins1884.frc2027.simulation;

import org.ironmaple.simulation.SimulatedArena;

/**
 * Empty MapleSim arena used until an official 2027 field model is available.
 *
 * <p>The arena intentionally contains no obstacles or game pieces; drivetrain physics remain
 * available without fabricating field geometry.
 */
public final class GenericSimArena extends SimulatedArena {
  private static boolean installed;

  private GenericSimArena() {
    super(new EmptyFieldMap());
  }

  public static synchronized void install() {
    if (!installed) {
      SimulatedArena.overrideInstance(new GenericSimArena());
      installed = true;
    }
  }

  @Override
  public void placeGamePiecesOnField() {
    // No 2027 game pieces are defined yet.
  }

  private static final class EmptyFieldMap extends FieldMap {}
}
