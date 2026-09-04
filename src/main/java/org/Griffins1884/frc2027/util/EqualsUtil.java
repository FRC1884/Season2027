package org.Griffins1884.frc2027.util;

import edu.wpi.first.math.geometry.Twist2d;

/** Reusable numeric comparison helpers. */
public final class EqualsUtil {
  private EqualsUtil() {}

  public static boolean epsilonEquals(double a, double b, double epsilon) {
    return (a - epsilon <= b) && (a + epsilon >= b);
  }

  public static boolean epsilonEquals(double a, double b) {
    return epsilonEquals(a, b, 1e-9);
  }

  public static final class GeomExtensions {
    private GeomExtensions() {}

    public static boolean epsilonEquals(Twist2d twist, Twist2d other) {
      return EqualsUtil.epsilonEquals(twist.dx, other.dx)
          && EqualsUtil.epsilonEquals(twist.dy, other.dy)
          && EqualsUtil.epsilonEquals(twist.dtheta, other.dtheta);
    }
  }
}
