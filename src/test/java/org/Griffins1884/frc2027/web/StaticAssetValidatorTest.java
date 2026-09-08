package org.Griffins1884.frc2027.web;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAssetValidatorTest {
  @TempDir Path root;

  @Test
  void matchesWeakStrongListsWildcardsAndQuotedCommas() {
    assertTrue(StaticAssetValidator.matches(List.of("W/\"abc\""), "W/\"abc\""));
    assertTrue(StaticAssetValidator.matches(List.of("\"abc\""), "W/\"abc\""));
    assertTrue(StaticAssetValidator.matches(List.of("\"other,tag\", W/\"abc\""), "W/\"abc\""));
    assertTrue(StaticAssetValidator.matches(List.of("\"other\"", " \tW/\"abc\" \t"), "W/\"abc\""));
    assertTrue(StaticAssetValidator.matches(List.of(" * \t"), "W/\"abc\""));
    assertFalse(StaticAssetValidator.matches(List.of("\"ABC\""), "W/\"abc\""));
    assertFalse(StaticAssetValidator.matches(List.of("\"*\""), "W/\"abc\""));
  }

  @Test
  void malformedFieldsNeverMatchEvenAfterMatchingPrefix() {
    for (String invalid :
        new String[] {
          "",
          "w/\"abc\"",
          "W/ \"abc\"",
          "\"abc\",",
          "\"abc\", broken",
          "*,\"abc\"",
          "\"abc\" extra",
          "\"abc",
          "\u000b\"abc\"",
          "\"bad tag\",\"abc\""
        }) {
      assertFalse(StaticAssetValidator.matches(List.of(invalid), "W/\"abc\""), invalid);
    }
    assertFalse(StaticAssetValidator.matches(null, "W/\"abc\""));
  }

  @Test
  void generationAndFullMetadataIdentifyTheWeakValidator() throws Exception {
    Path file = root.resolve("index.js");
    Files.writeString(file, "same");
    var attrs = Files.readAttributes(file, BasicFileAttributes.class);
    String tag = StaticAssetValidator.tag("first", Path.of("index.js"), attrs);
    assertTrue(tag.startsWith("W/\""));
    assertEquals(tag, StaticAssetValidator.tag("first", Path.of("index.js"), attrs));
    assertNotEquals(tag, StaticAssetValidator.tag("second", Path.of("index.js"), attrs));
    assertNotEquals(tag, StaticAssetValidator.tag("first", Path.of("NT4.js"), attrs));
  }
}
