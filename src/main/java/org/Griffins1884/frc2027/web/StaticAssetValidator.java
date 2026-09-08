package org.Griffins1884.frc2027.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Metadata validators for assets immutable between server restarts; never a content hash. */
final class StaticAssetValidator {
  private StaticAssetValidator() {}

  static String tag(String generation, Path relativePath, BasicFileAttributes attributes) {
    // Hash only bounded representation metadata, never file content or the deploy tree.
    String metadata =
        generation
            + "\n"
            + relativePath
            + "\n"
            + attributes.size()
            + "\n"
            + attributes.lastModifiedTime()
            + "\n"
            + attributes.creationTime()
            + "\n"
            + attributes.fileKey();
    try {
      return "W/\""
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(metadata.getBytes(StandardCharsets.UTF_8)))
          + "\"";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Java requires SHA-256", exception);
    }
  }

  /** RFC 9110 weak comparison, validating the entire field before returning a match. */
  static boolean matches(List<String> values, String selectedTag) {
    if (values == null || values.isEmpty()) return false;
    String raw = String.join(",", values);
    int first = 0, last = raw.length();
    while (first < last && ows(raw.charAt(first))) first++;
    while (last > first && ows(raw.charAt(last - 1))) last--;
    String field = raw.substring(first, last);
    if (field.equals("*")) return true;
    String selected = selectedTag.startsWith("W/") ? selectedTag.substring(2) : selectedTag;
    int index = 0;
    boolean matched = false;
    boolean found = false;
    while (index < field.length()) {
      while (index < field.length() && ows(field.charAt(index))) index++;
      if (field.startsWith("W/", index)) index += 2;
      if (index >= field.length() || field.charAt(index) != '"') return false;
      int start = index++;
      while (index < field.length() && field.charAt(index) != '"') {
        char c = field.charAt(index++);
        if (!(c == 0x21 || (c >= 0x23 && c <= 0x7e) || (c >= 0x80 && c <= 0xff))) return false;
      }
      if (index == field.length()) return false;
      index++;
      matched |= field.substring(start, index).equals(selected);
      found = true;
      while (index < field.length() && ows(field.charAt(index))) index++;
      if (index == field.length()) return found && matched;
      if (field.charAt(index++) != ',') return false;
      if (index == field.length()) return false;
    }
    return false;
  }

  private static boolean ows(char c) {
    return c == ' ' || c == '\t';
  }
}
