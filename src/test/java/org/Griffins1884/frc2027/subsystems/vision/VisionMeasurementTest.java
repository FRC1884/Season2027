package org.Griffins1884.frc2027.subsystems.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Transform3d;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VisionMeasurementTest {
  @Test
  void acceptsGenericAprilTagPoseAndHonorsExclusiveTagFilter() {
    AtomicInteger acceptedCount = new AtomicInteger();
    FakeVisionIO io = new FakeVisionIO();
    Vision vision = new Vision((pose, timestamp, stdDevs) -> acceptedCount.incrementAndGet(), io);

    io.setEstimate(1.0, 7);
    vision.periodic();
    assertEquals(1, acceptedCount.get());

    vision.setExclusiveTagId(12);
    io.setEstimate(2.0, 7);
    vision.periodic();
    assertEquals(1, acceptedCount.get());
  }

  private static final class FakeVisionIO implements VisionIO {
    private final VisionIOInputs next = new VisionIOInputs();

    private void setEstimate(double timestamp, int tagId) {
      next.connected = true;
      next.megatagCount = 1;
      next.standardDeviations = AprilTagVisionConstants.getLimelightStandardDeviations();
      next.megatagPoseEstimate =
          new MegatagPoseEstimate(
              new Pose2d(1.0, 2.0, new edu.wpi.first.math.geometry.Rotation2d()),
              timestamp,
              0.0,
              1.0,
              1.0,
              1.0,
              new int[] {tagId},
              0.0);
    }

    @Override
    public void updateInputs(VisionIOInputs inputs) {
      inputs.connected = next.connected;
      inputs.megatagCount = next.megatagCount;
      inputs.standardDeviations = next.standardDeviations;
      inputs.megatagPoseEstimate = next.megatagPoseEstimate;
    }

    @Override
    public CameraConstants getCameraConstants() {
      return new CameraConstants("test-camera", new Transform3d(), CameraType.LIMELIGHT);
    }
  }
}
