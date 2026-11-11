/*
 * Copyright (C) 2026 cribro contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.cribrodev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The key JaCoCo and PIT record a source file by: the package of its classes as a path, then the
 * file name, as in {@code com/example/Sample.kt}. A file in the default package is keyed by its
 * name alone, {@code Sample.kt}, as JaCoCo names that package with an empty string.
 *
 * <p>A class file records its package and, in its {@code SourceFile} attribute, the name of the
 * file it was compiled from, but not the directory the file lay in. So a tool that sees only
 * bytecode knows a file by this key alone. For Java the directory below the source root follows
 * the package by convention; Kotlin lets a file declare any package wherever it lies, and Kotlin
 * projects often drop the leading directories. The key is therefore read from the {@code package}
 * declaration in the file, and the path below the source root stands in where the file cannot be
 * read or holds no readable declaration, as {@link #declaredPackage(String, boolean)} defines one.
 *
 * <p>Every reader of a JaCoCo report or a PIT mutant builds the key through {@link #of(String,
 * String)}, so the rule has one home.
 */
public final class SourceFileKey {

  /** The extensions of the source files cribro measures. */
  static final List<String> EXTENSIONS = List.of(".java", ".kt");

  private SourceFileKey() {}

  /** Returns the git pathspecs that select the source files cribro measures. */
  static List<String> pathspecs() {
    return EXTENSIONS.stream().map(extension -> "*" + extension).collect(Collectors.toList());
  }

  /** Returns whether the path names a source file cribro measures, by its extension. */
  public static boolean isSource(String path) {
    return EXTENSIONS.stream().anyMatch(path::endsWith);
  }

  /**
   * Returns the key of a file in a package, as a JaCoCo report or a PIT mutant names them.
   *
   * @param packagePath the package with {@code /} between its segments, as JaCoCo and a class
   *     file's internal name spell it; empty or null for the default package
   * @param fileName the name of the file, as the {@code SourceFile} attribute records it
   */
  public static String of(String packagePath, String fileName) {
    return packagePath == null || packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
  }

  /**
   * Returns the key of a source file of the working tree.
   *
   * @param repository the repository root
   * @param path the file, relative to the repository and spelled with {@code /} as git prints it
   * @param relativeToRoot the path below the source root that holds the file, which is the key
   *     where the file cannot be read or holds no readable package declaration
   */
  static String of(File repository, String path, String relativeToRoot) {
    String declared;
    try {
      // Decoded leniently: a comment in another charset must not hide the declaration.
      declared =
          declaredPackage(
              new String(
                  Files.readAllBytes(repository.toPath().resolve(path)), StandardCharsets.UTF_8),
              path.endsWith(".kt"));
    } catch (IOException | InvalidPathException e) {
      return relativeToRoot;
    }
    if (declared == null) {
      return relativeToRoot;
    }
    return of(declared.replace('.', '/'), path.substring(path.lastIndexOf('/') + 1));
  }

  /**
   * Returns the package a Java or Kotlin source declares, an empty string where it declares none,
   * or null where the text has no readable declaration, as with an unterminated comment, {@code
   * package ;}, or a {@code package} keyword that ends the text.
   *
   * <p>Only comments and annotations, Java package annotations and Kotlin file annotations alike,
   * may precede the declaration, and in Kotlin a {@code #!} line before them, so the text is
   * scanned rather than searched: a string literal or a comment further down that spells {@code
   * package} names no package. A text declares none only where the scan ends at the end of the
   * text, at an empty declaration {@code ;}, or at an identifier other than {@code package}, such
   * as {@code import} or {@code class}; any other character there leaves the declaration unread.
   *
   * @param kotlin whether the text is Kotlin, which may open with a {@code #!} line and whose block
   *     comments nest, so that a comment holding another ends at the matching close rather than the
   *     first
   */
  static String declaredPackage(String text, boolean kotlin) {
    int at = text.startsWith("﻿") ? 1 : 0;
    if (kotlin && text.startsWith("#!", at)) {
      at = skipLineComment(text, at);
    }
    at = skipSpaceCommentsAndAnnotations(text, at, kotlin);
    if (at < 0) {
      return null;
    }
    if (at == text.length()) {
      return "";
    }
    if (!text.startsWith("package", at)) {
      char c = text.charAt(at);
      return Character.isJavaIdentifierStart(c) || c == ';' ? "" : null;
    }
    at += "package".length();
    if (at < text.length() && Character.isJavaIdentifierPart(text.charAt(at))) {
      return "";
    }
    StringBuilder name = new StringBuilder();
    while (true) {
      at = skipSpaceAndComments(text, at, kotlin);
      if (at < 0 || at >= text.length()) {
        return null;
      }
      int start = at;
      if (text.charAt(at) == '`') {
        int end = text.indexOf('`', at + 1);
        if (end < 0) {
          return null;
        }
        name.append(text, at + 1, end);
        at = end + 1;
      } else {
        if (!Character.isJavaIdentifierStart(text.charAt(at))) {
          return null;
        }
        while (at < text.length() && Character.isJavaIdentifierPart(text.charAt(at))) {
          at++;
        }
        name.append(text, start, at);
      }
      at = skipSpaceAndComments(text, at, kotlin);
      if (at < 0) {
        return null;
      }
      if (at < text.length() && text.charAt(at) == '.') {
        name.append('.');
        at++;
        continue;
      }
      return name.toString();
    }
  }

  /**
   * Returns the index of the first character after the whitespace, comments, and annotations at
   * {@code at}, or -1 where one of them never ends.
   *
   * @param nestedComments whether a block comment may hold another, as in Kotlin
   */
  private static int skipSpaceCommentsAndAnnotations(
      String text, int at, boolean nestedComments) {
    at = skipSpaceAndComments(text, at, nestedComments);
    while (at >= 0 && at < text.length() && text.charAt(at) == '@') {
      at = skipSpaceAndComments(text, skipAnnotation(text, at + 1, nestedComments), nestedComments);
    }
    return at;
  }

  /**
   * Returns the index of the first character after the whitespace and comments at {@code at}, or -1
   * where {@code at} is -1 or a comment never ends.
   */
  private static int skipSpaceAndComments(String text, int at, boolean nestedComments) {
    while (at >= 0 && at < text.length()) {
      if (Character.isWhitespace(text.charAt(at))) {
        at++;
      } else if (text.startsWith("//", at)) {
        at = skipLineComment(text, at);
      } else if (text.startsWith("/*", at)) {
        at = skipBlockComment(text, at, nestedComments);
      } else {
        break;
      }
    }
    return at;
  }

  /**
   * Returns the index of the line terminator, LF or CR, that ends the line holding {@code at}, or
   * the length of a text the line ends.
   */
  private static int skipLineComment(String text, int at) {
    while (at < text.length() && text.charAt(at) != '\n' && text.charAt(at) != '\r') {
      at++;
    }
    return at;
  }

  /**
   * Returns the index after the block comment that opens at {@code at}, or -1 where it never
   * closes. With nesting, each comment opened inside it needs a close of its own.
   */
  private static int skipBlockComment(String text, int at, boolean nested) {
    int depth = 1;
    at += 2;
    while (at < text.length()) {
      if (text.startsWith("*/", at)) {
        depth--;
        at += 2;
        if (depth == 0) {
          return at;
        }
      } else if (nested && text.startsWith("/*", at)) {
        depth++;
        at += 2;
      } else {
        at++;
      }
    }
    return -1;
  }

  /**
   * Returns the index after the annotation whose name starts at {@code at}, or -1 where a comment
   * or the arguments never end: a Kotlin use-site target such as {@code file:}, a qualified name,
   * and the arguments in parentheses or, for a Kotlin annotation list, in brackets. Whitespace and
   * comments may stand between these parts.
   */
  private static int skipAnnotation(String text, int at, boolean nestedComments) {
    at = skipSpaceAndComments(text, at, nestedComments);
    while (at >= 0) {
      while (at < text.length()
          && (Character.isJavaIdentifierPart(text.charAt(at)) || text.charAt(at) == '.')) {
        at++;
      }
      int next = skipSpaceAndComments(text, at, nestedComments);
      if (next < 0) {
        return -1;
      }
      if (next >= text.length()) {
        return at;
      }
      char c = text.charAt(next);
      if (c == ':') {
        at = skipSpaceAndComments(text, next + 1, nestedComments);
        continue;
      }
      if (c == '(') {
        return skipBalanced(text, next, '(', ')', nestedComments);
      }
      if (c == '[') {
        return skipBalanced(text, next, '[', ']', nestedComments);
      }
      return at;
    }
    return -1;
  }

  /**
   * Returns the index after the bracket that closes the one at {@code at}, or -1 where none does.
   * A comment or a string or character literal between them is skipped, so a bracket in it does
   * not count.
   */
  private static int skipBalanced(
      String text, int at, char open, char close, boolean nestedComments) {
    int depth = 0;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == open) {
        depth++;
      } else if (c == close) {
        depth--;
        if (depth == 0) {
          return at + 1;
        }
      } else if (text.startsWith("//", at)) {
        at = skipLineComment(text, at);
        continue;
      } else if (text.startsWith("/*", at)) {
        at = skipBlockComment(text, at, nestedComments);
        if (at < 0) {
          return -1;
        }
        continue;
      } else if (c == '"' || c == '\'') {
        at = skipLiteral(text, at);
        if (at < 0) {
          return -1;
        }
        continue;
      }
      at++;
    }
    return -1;
  }

  /**
   * Returns the index after the literal that opens at {@code at}, or -1 where it never closes. A
   * Kotlin raw string ends at the last quote of the first run of three or more, so the quotes
   * before the last three belong to its text.
   */
  private static int skipLiteral(String text, int at) {
    char quote = text.charAt(at);
    if (quote == '"' && text.startsWith("\"\"\"", at)) {
      int end = text.indexOf("\"\"\"", at + 3);
      if (end < 0) {
        return -1;
      }
      end += 3;
      while (end < text.length() && text.charAt(end) == '"') {
        end++;
      }
      return end;
    }
    at++;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == '\\') {
        at += 2;
        continue;
      }
      if (c == quote) {
        return at + 1;
      }
      at++;
    }
    return -1;
  }
}
