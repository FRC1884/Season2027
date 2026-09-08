package org.Griffins1884.frc2027.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NtLoadDeliveryTest {
  @Test
  void connectionOrPartialDeliveryCannotReportSuccessfulLoad() {
    assertTrue(NtLoadMain.completeDelivery(100, 100, 400, 4, 0));
    assertFalse(NtLoadMain.completeDelivery(100, 100, 399, 4, 1));
    assertFalse(NtLoadMain.completeDelivery(100, 99, 396, 4, 0));
    assertFalse(NtLoadMain.completeDelivery(100, 100, 400, 4, 1));
    assertFalse(NtLoadMain.completeDelivery(-1, -1, -4, 4, 0));
  }
}
