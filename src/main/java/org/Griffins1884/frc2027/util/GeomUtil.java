package org.Griffins1884.frc2027.util;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;

/** Reusable geometry helpers carried forward from Season2026. */
public final class GeomUtil {
    private GeomUtil() {}

    public static Transform2d toTransform2d(Translation2d translation) {
        return new Transform2d(translation, Rotation2d.kZero);
    }

    public static Transform2d toTransform2d(double x, double y) {
        return new Transform2d(x, y, Rotation2d.kZero);
    }

    public static Transform2d toTransform2d(Rotation2d rotation) {
        return new Transform2d(Translation2d.kZero, rotation);
    }

    public static Transform2d toTransform2d(Pose2d pose) {
        return new Transform2d(pose.getTranslation(), pose.getRotation());
    }

    public static Pose2d inverse(Pose2d pose) {
        Rotation2d rotationInverse = pose.getRotation().unaryMinus();
        return new Pose2d(
                pose.getTranslation().unaryMinus().rotateBy(rotationInverse), rotationInverse);
    }

    public static Pose2d toPose2d(Transform2d transform) {
        return new Pose2d(transform.getTranslation(), transform.getRotation());
    }

    public static Pose2d toPose2d(Translation2d translation) {
        return new Pose2d(translation, Rotation2d.kZero);
    }

    public static Pose2d toPose2d(Rotation2d rotation) {
        return new Pose2d(Translation2d.kZero, rotation);
    }

    public static Twist2d multiply(Twist2d twist, double factor) {
        return new Twist2d(twist.dx * factor, twist.dy * factor, twist.dtheta * factor);
    }

    public static Transform3d toTransform3d(Pose3d pose) {
        return new Transform3d(pose.getTranslation(), pose.getRotation());
    }

    public static Pose3d toPose3d(Transform3d transform) {
        return new Pose3d(transform.getTranslation(), transform.getRotation());
    }

    public static Twist2d toTwist2d(ChassisSpeeds speeds) {
        return new Twist2d(
                speeds.vxMetersPerSecond,
                speeds.vyMetersPerSecond,
                speeds.omegaRadiansPerSecond);
    }

    public static Pose2d withTranslation(Pose2d pose, Translation2d translation) {
        return new Pose2d(translation, pose.getRotation());
    }

    public static Pose2d withRotation(Pose2d pose, Rotation2d rotation) {
        return new Pose2d(pose.getTranslation(), rotation);
    }
}
