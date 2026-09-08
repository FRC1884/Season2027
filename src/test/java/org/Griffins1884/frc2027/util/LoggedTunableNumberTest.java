package org.Griffins1884.frc2027.util;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.Griffins1884.frc2027.GlobalConstants;
import org.Griffins1884.frc2027.runtime.RuntimeModeManager;
import org.Griffins1884.frc2027.runtime.RuntimeModeProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.networktables.LoggedNetworkNumber;

class LoggedTunableNumberTest {
  @AfterEach
  void restoreProfile() {
    RuntimeModeManager.resetToDefaults();
  }

  private void tuning(boolean enabled) {
    RuntimeModeManager.setActiveProfile(
        new RuntimeModeProfile(GlobalConstants.LoggingMode.COMP, enabled, Set.of(), null, null));
  }

  @Test
  void initialChecksAdvanceAllNumbersWithoutRepeatedApplication() {
    tuning(false);
    var first = new LoggedTunableNumber("test/first", 1.0);
    var second = new LoggedTunableNumber("test/second", 2.0);
    var third = new LoggedTunableNumber("test/third", 3.0);
    AtomicInteger calls = new AtomicInteger();
    for (int i = 0; i < 4; i++) {
      LoggedTunableNumber.ifChanged(
          17,
          values -> {
            assertArrayEquals(new double[] {1.0, 2.0, 3.0}, values);
            calls.incrementAndGet();
          },
          first,
          second,
          third);
    }
    assertEquals(1, calls.get());
  }

  @Test
  void defaultInitializesOnlyOnce() {
    tuning(false);
    var number = new LoggedTunableNumber("test/default");
    assertEquals(0.0, number.get());
    number.initDefault(4.0);
    number.initDefault(9.0);
    assertEquals(4.0, number.get());
  }

  @Test
  void lateTuningRetainsExistingDashboardValueAndSingleInputAcrossTransitions() throws Exception {
    tuning(false);
    var number = new LoggedTunableNumber("test/late", 4.0);
    var entry =
        NetworkTableInstance.getDefault().getEntry("/SmartDashboard/TunableNumbers/test/late");
    entry.setDouble(7.0);
    assertEquals(4.0, number.get());
    tuning(true);
    assertEquals(7.0, number.get());
    var input = input(number);
    tuning(false);
    entry.setDouble(8.0);
    input.periodic(); // The logger continues acquiring registered inputs while tuning is disabled.
    assertEquals(4.0, number.get());
    tuning(true);
    assertEquals(8.0, number.get());
    assertSame(input, input(number));
  }

  @Test
  void simultaneousDashboardChangesApplyOnce() throws Exception {
    tuning(true);
    var first = new LoggedTunableNumber("test/multipleFirst", 1.0);
    var second = new LoggedTunableNumber("test/multipleSecond", 2.0);
    AtomicInteger calls = new AtomicInteger();
    LoggedTunableNumber.ifChanged(9, calls::incrementAndGet, first, second);
    input(first).set(11.0);
    input(second).set(12.0);
    input(first).periodic();
    input(second).periodic();
    LoggedTunableNumber.ifChanged(
        9,
        values -> {
          assertArrayEquals(new double[] {11.0, 12.0}, values);
          calls.incrementAndGet();
        },
        first,
        second);
    LoggedTunableNumber.ifChanged(9, calls::incrementAndGet, first, second);
    assertEquals(2, calls.get());
  }

  private LoggedNetworkNumber input(LoggedTunableNumber number) throws Exception {
    Field field = LoggedTunableNumber.class.getDeclaredField("dashboardNumber");
    field.setAccessible(true);
    return (LoggedNetworkNumber) field.get(number);
  }
}
