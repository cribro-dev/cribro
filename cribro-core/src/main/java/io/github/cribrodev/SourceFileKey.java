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
   * package} names no package. The scan splits the text into tokens by the rules of the language,
   * so a quote, a bracket, or a comment opener inside a literal or a comment ends nothing. A text
   * declares none only where the scan ends at the end of the text, at an empty declaration {@code
   * ;}, or at an identifier other than {@code package}, such as {@code import} or {@code class};
   * any other character there leaves the declaration unread.
   *
   * @param kotlin whether the text is Kotlin, which may open with a {@code #!} line, whose block
   *     comments nest, and whose strings hold templates; a Java text is scanned after its Unicode
   *     escapes are replaced, as javac reads it
   */
  static String declaredPackage(String text, boolean kotlin) {
    if (!kotlin) {
      text = translateUnicodeEscapes(text);
    }
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
      int c = text.codePointAt(at);
      return Character.isJavaIdentifierStart(c) || c == ';' ? "" : null;
    }
    at += "package".length();
    if (at < text.length() && Character.isJavaIdentifierPart(text.codePointAt(at))) {
      return "";
    }
    StringBuilder name = new StringBuilder();
    at = skipQualifiedName(text, skipSpaceAndComments(text, at, kotlin), kotlin, name);
    return at < 0 ? null : name.toString();
  }

  /**
   * Returns the text with each Unicode escape replaced by the character it stands for, as javac
   * reads a source before it splits it into tokens (JLS §3.3). A backslash begins an escape only
   * where an even number of backslashes precede it, and a character an escape produces begins
   * none. A backslash and {@code u} that no four hexadecimal digits follow stay as they are.
   */
  private static String translateUnicodeEscapes(String text) {
    StringBuilder out = new StringBuilder(text.length());
    int backslashes = 0;
    int at = 0;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == '\\' && backslashes % 2 == 0) {
        int digits = at + 1;
        while (digits < text.length() && text.charAt(digits) == 'u') {
          digits++;
        }
        if (digits > at + 1 && isHex(text, digits, digits + 4)) {
          out.append((char) Integer.parseInt(text, digits, digits + 4, 16));
          at = digits + 4;
          backslashes = 0;
          continue;
        }
      }
      backslashes = c == '\\' ? backslashes + 1 : 0;
      out.append(c);
      at++;
    }
    return out.toString();
  }

  /** Returns whether the text holds ASCII hexadecimal digits from {@code from} to {@code to}. */
  private static boolean isHex(String text, int from, int to) {
    if (to > text.length()) {
      return false;
    }
    for (int at = from; at < to; at++) {
      if ("0123456789abcdefABCDEF".indexOf(text.charAt(at)) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the index of the first character after the whitespace, comments, and annotations at
   * {@code at}, or -1 where one of them never ends.
   */
  private static int skipSpaceCommentsAndAnnotations(String text, int at, boolean kotlin) {
    at = skipSpaceAndComments(text, at, kotlin);
    while (at >= 0 && at < text.length() && text.charAt(at) == '@') {
      at = skipSpaceAndComments(text, skipAnnotation(text, at + 1, kotlin), kotlin);
    }
    return at;
  }

  /**
   * Returns the index of the first character after the whitespace and comments at {@code at}, or -1
   * where {@code at} is -1 or a comment never ends.
   *
   * @param nestedComments whether a block comment may hold another, as in Kotlin
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
   * Returns the index after the annotation whose name starts at {@code at}, or -1 where a comment,
   * the name, or the arguments never end: a Kotlin use-site target such as {@code file:}, a
   * qualified name, Kotlin type arguments in angle brackets, and the arguments in parentheses or,
   * for a Kotlin annotation list, the annotations in brackets. Whitespace and comments may stand
   * between these parts and around each dot of the name.
   */
  private static int skipAnnotation(String text, int at, boolean kotlin) {
    at = skipSpaceAndComments(text, at, kotlin);
    while (at >= 0 && at < text.length()) {
      if (text.charAt(at) == '[') {
        return skipBalanced(text, at, '[', ']', kotlin);
      }
      int end = skipQualifiedName(text, at, kotlin, null);
      int next = skipSpaceAndComments(text, end, kotlin);
      if (kotlin && next >= 0 && next < text.length() && text.charAt(next) == '<') {
        end = skipTypeArguments(text, next);
        next = skipSpaceAndComments(text, end, true);
      }
      if (next < 0) {
        return -1;
      }
      if (next < text.length() && text.charAt(next) == ':') {
        at = skipSpaceAndComments(text, next + 1, kotlin);
        continue;
      }
      if (next < text.length() && text.charAt(next) == '(') {
        return skipBalanced(text, next, '(', ')', kotlin);
      }
      return end;
    }
    return at;
  }

  /**
   * Returns the index after the Kotlin type arguments that open at {@code at}, or -1 where the
   * angle brackets never close. The {@code >} of a function type's {@code ->} closes none, and a
   * comment between them is skipped.
   */
  private static int skipTypeArguments(String text, int at) {
    int depth = 0;
    while (at >= 0 && at < text.length()) {
      char c = text.charAt(at);
      if (text.startsWith("//", at)) {
        at = skipLineComment(text, at);
      } else if (text.startsWith("/*", at)) {
        at = skipBlockComment(text, at, true);
      } else if (text.startsWith("->", at)) {
        at += 2;
      } else {
        if (c == '<') {
          depth++;
        } else if (c == '>' && --depth == 0) {
          return at + 1;
        }
        at++;
      }
    }
    return -1;
  }

  /**
   * Returns the index after the qualified name at {@code at}, or -1 where {@code at} is -1, no
   * identifier starts there or after a dot, or a comment or a backticked identifier never ends.
   * Whitespace and comments may stand around each dot.
   *
   * @param name receives the identifiers joined by dots, without backticks; null where only the end
   *     of the name matters
   */
  private static int skipQualifiedName(
      String text, int at, boolean kotlin, StringBuilder name) {
    while (true) {
      int end = skipIdentifier(text, at);
      if (end <= at) {
        return -1;
      }
      if (name != null) {
        boolean backticked = text.charAt(at) == '`';
        name.append(text, backticked ? at + 1 : at, backticked ? end - 1 : end);
      }
      int dot = skipSpaceAndComments(text, end, kotlin);
      if (dot < 0) {
        return -1;
      }
      if (dot >= text.length() || text.charAt(dot) != '.') {
        return end;
      }
      if (name != null) {
        name.append('.');
      }
      at = skipSpaceAndComments(text, dot + 1, kotlin);
    }
  }

  /**
   * Returns the index after the identifier or the backticked name at {@code at}: {@code at} itself
   * where neither starts there, and -1 where {@code at} is -1 or the backticks never close.
   */
  private static int skipIdentifier(String text, int at) {
    if (at < 0 || at >= text.length()) {
      return at;
    }
    if (text.charAt(at) == '`') {
      int close = text.indexOf('`', at + 1);
      return close < 0 ? -1 : close + 1;
    }
    int end = at;
    while (end < text.length()) {
      int c = text.codePointAt(end);
      if (end == at ? !Character.isJavaIdentifierStart(c) : !Character.isJavaIdentifierPart(c)) {
        break;
      }
      end += Character.charCount(c);
    }
    return end;
  }

  /**
   * Returns the index after the bracket that closes the one at {@code at}, or -1 where none does.
   * A comment, a string or character literal, or a Kotlin identifier in backticks between them is
   * skipped, so a bracket in it does not count.
   */
  private static int skipBalanced(String text, int at, char open, char close, boolean kotlin) {
    int depth = 0;
    int dollars = 0;
    while (at >= 0 && at < text.length()) {
      char c = text.charAt(at);
      if (text.startsWith("//", at)) {
        at = skipLineComment(text, at);
      } else if (text.startsWith("/*", at)) {
        at = skipBlockComment(text, at, kotlin);
      } else if (c == '"' || c == '\'') {
        at = skipLiteral(text, at, kotlin, dollars);
      } else if (kotlin && c == '`') {
        at = skipIdentifier(text, at);
      } else {
        if (c == open) {
          depth++;
        } else if (c == close && --depth == 0) {
          return at + 1;
        }
        at++;
      }
      dollars = c == '$' ? dollars + 1 : 0;
    }
    return -1;
  }

  /**
   * Returns the index after the literal that opens at {@code at}, or -1 where it never closes.
   *
   * <p>A backslash escapes the character after it, except in a Kotlin raw string. A Java text block
   * or a Kotlin raw string ends at the last quote of the first run of three or more unescaped
   * quotes. In a Kotlin raw string, the quotes before the last three of that run belong to the
   * string. A Java source that compiles has no quote right after the closing delimiter of a text
   * block, so ending a text block at the last quote of the run reads every such source. In a
   * Kotlin string, a brace right after at least as many dollar signs as stand before the opening
   * quote, and at least one, opens a template, which ends at the matching brace and may hold
   * literals and comments of its own.
   *
   * @param dollars the number of dollar signs right before the opening quote, the template prefix
   *     of a Kotlin string
   */
  private static int skipLiteral(String text, int at, boolean kotlin, int dollars) {
    char quote = text.charAt(at);
    boolean multiline = quote == '"' && text.startsWith("\"\"\"", at);
    boolean escapes = !(kotlin && multiline);
    boolean templates = kotlin && quote == '"';
    int prefix = Math.max(1, dollars);
    int run = 0;
    at += multiline ? 3 : 1;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (multiline && text.startsWith("\"\"\"", at)) {
        at += 3;
        while (at < text.length() && text.charAt(at) == '"') {
          at++;
        }
        return at;
      }
      if (!multiline && c == quote) {
        return at + 1;
      }
      if (c == '\\' && escapes) {
        at += 2;
        run = 0;
      } else if (c == '{' && templates && run >= prefix) {
        at = skipBalanced(text, at, '{', '}', true);
        if (at < 0) {
          return -1;
        }
        run = 0;
      } else {
        run = c == '$' ? run + 1 : 0;
        at++;
      }
    }
    return -1;
  }
}
