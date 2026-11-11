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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The key of a source file is read from the package it declares, as the class files compiled from
 * it record it, so that a Kotlin file in a directory that does not follow its package is still
 * matched to its JaCoCo counters and its PIT mutants.
 */
class SourceFileKeyTest {

  @TempDir Path repository;

  /** Prints a source text on one line, so that the failure report shows where it breaks. */
  private static String declaredPackageOf(String text, boolean kotlin) {
    return "declaredPackage(\"" + text.replace("\n", "\\n") + "\", kotlin=" + kotlin + ")";
  }

  @Nested
  class TheDeclaredPackage {

    static Stream<Arguments> preambles() {
      return Stream.of(
          argumentSet("nothing", "package demo\n", false),
          argumentSet("a byte order mark", "\uFEFFpackage demo\n", false),
          argumentSet("blank lines", "\n\n  package demo\n", false),
          argumentSet("line comments", "// package not.this\n// x\npackage demo\n", false),
          argumentSet("a block comment", "/*\n * package not.this;\n */\npackage demo\n", false),
          argumentSet("a nested Kotlin comment", "/* a /* b */ c */\npackage demo\n", true),
          argumentSet("a Java package annotation", "@Deprecated\npackage demo;\n", false),
          argumentSet("a Kotlin file annotation", "@file:JvmName(\"U\")\npackage demo\n", true),
          argumentSet(
              "two file annotations",
              "@file:JvmName(\"U\")\n@file:Suppress(\"a\", \"b\")\npackage demo\n",
              true),
          argumentSet(
              "a qualified annotation name",
              "@file:kotlin.jvm.JvmName(\"U\")\npackage demo\n",
              true),
          argumentSet(
              "spaces around the use-site target and the arguments",
              "@file : JvmName (\"U\")\npackage demo\n",
              true),
          argumentSet(
              "an annotation list",
              "@file:[JvmName(\"U\") Suppress(\"x\")]\npackage demo\n",
              true),
          argumentSet(
              "a bracket in a string argument", "@file:JvmName(\"U)\")\npackage demo\n", true),
          argumentSet(
              "an escaped quote before a bracket in a string argument",
              "@file:JvmName(\"ab\\\")\")\npackage demo\n",
              true),
          argumentSet(
              "a quote and brackets in a raw string argument",
              "@file:JvmName(\"\"\"a\"b)))\"\"\")\npackage demo\n",
              true),
          argumentSet(
              "a bracket in a character argument", "@file:Suppress(')')\npackage demo\n", true),
          argumentSet(
              "nested brackets in the arguments",
              "@file:Suppress(names = [(\"a\")])\npackage demo\n",
              true));
    }

    @ParameterizedTest
    @MethodSource("preambles")
    void theDeclarationIsReadPastWhatPrecedesIt(String text, boolean kotlin) {
      assertEquals(
          "demo", SourceFileKey.declaredPackage(text, kotlin), declaredPackageOf(text, kotlin));
    }

    static Stream<Arguments> names() {
      return Stream.of(
          argumentSet("Java, ended by a semicolon", "package com.x.y;\n\nclass A {}\n", "com.x.y"),
          argumentSet(
              "Kotlin, ended by the line", "package demo.math\n\nfun f() = 1\n", "demo.math"),
          argumentSet("ended by the text", "package demo.math", "demo.math"),
          argumentSet(
              "spaces and a comment between the segments",
              "package com . /* c */ x ;\n",
              "com.x"),
          argumentSet("a backticked last segment", "package demo.`my pkg`\n", "demo.my pkg"),
          argumentSet("a backticked first segment", "package `my pkg`.demo\n", "my pkg.demo"),
          argumentSet("an identifier with digits and underscores", "package a_1.b2;\n", "a_1.b2"));
    }

    @ParameterizedTest
    @MethodSource("names")
    void theNameIsReadWithoutSpacesCommentsOrBackticks(String text, String expected) {
      assertEquals(
          expected, SourceFileKey.declaredPackage(text, true), declaredPackageOf(text, true));
    }

    static Stream<Arguments> textsWithoutADeclaration() {
      return Stream.of(
          argumentSet("an empty text", ""),
          argumentSet("a class", "class A {}\n"),
          argumentSet("a call whose name starts with the keyword", "packageInfo()\n"),
          argumentSet("a line comment that ends the text", "// note"),
          argumentSet("an annotated class", "@Deprecated\nclass A {}\n"),
          argumentSet("an annotation that ends the text", "@Deprecated"),
          argumentSet("an annotation followed by a newline that ends the text", "@Deprecated\n"),
          argumentSet("a use-site target that ends the text", "@file:"),
          argumentSet(
              "a string further down that spells a declaration",
              "class A {\n  String s = \"\"\"\n      package demo;\n      \"\"\";\n}\n"));
    }

    @ParameterizedTest
    @MethodSource("textsWithoutADeclaration")
    void aTextThatDeclaresNoPackageIsInTheDefaultPackage(String text) {
      assertEquals("", SourceFileKey.declaredPackage(text, true), declaredPackageOf(text, true));
    }

    static Stream<Arguments> unreadableTexts() {
      return Stream.of(
          argumentSet("an unterminated block comment", "/* package com.x;\n"),
          argumentSet("an unterminated nested Kotlin comment", "/* a /* b */ package x;\n"),
          argumentSet("an unterminated argument list", "@file:JvmName(\"X\"\npackage demo\n"),
          argumentSet("an unterminated string argument", "@file:JvmName(\"X)\npackage demo\n"),
          argumentSet(
              "an unterminated raw string argument", "@file:JvmName(\"\"\"X\"Y)\npackage demo\n"),
          argumentSet("a keyword that ends the text", "package"),
          argumentSet("a keyword and a space that end the text", "package "),
          argumentSet("an unterminated comment after the keyword", "package /* x"),
          argumentSet("a keyword with no name", "package ;\n"),
          argumentSet("a name that starts with a digit", "package 1x;\n"),
          argumentSet("a name that ends in a dot", "package demo."),
          argumentSet("an unterminated backticked segment", "package demo.`x"));
    }

    @ParameterizedTest
    @MethodSource("unreadableTexts")
    void aTextWhoseDeclarationCannotBeReadHasNoPackage(String text) {
      assertEquals(null, SourceFileKey.declaredPackage(text, true), declaredPackageOf(text, true));
    }

    static Stream<Arguments> commentNesting() {
      return Stream.of(argumentSet("Java", false, "x"), argumentSet("Kotlin", true, "y"));
    }

    @ParameterizedTest
    @MethodSource("commentNesting")
    void aBlockCommentEndsAtItsMatchingCloseOnlyInKotlin(boolean kotlin, String expected) {
      String text = "/* a /* b */ package x; */\npackage y;\n";
      assertEquals(
          expected, SourceFileKey.declaredPackage(text, kotlin), declaredPackageOf(text, kotlin));
    }
  }

  @Nested
  class TheKeyOfAFileInTheWorkingTree {

    private void write(String path, String text) throws IOException {
      Path file = repository.resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, text);
    }

    @Test
    void isTheDeclaredPackageAsAPathAndTheFileName() throws IOException {
      write("src/main/kotlin/Calc.kt", "package demo.math\n");
      assertEquals(
          "demo/math/Calc.kt",
          SourceFileKey.of(repository.toFile(), "src/main/kotlin/Calc.kt", "Calc.kt"));
    }

    static Stream<Arguments> extensions() {
      return Stream.of(
          argumentSet("a Kotlin file", "src/Calc.kt", "y/Calc.kt"),
          argumentSet("a Java file", "src/Calc.java", "x/Calc.java"));
    }

    @ParameterizedTest
    @MethodSource("extensions")
    void isReadWithNestedCommentsOnlyForAKotlinFile(String path, String expected)
        throws IOException {
      write(path, "/* a /* b */ package x; */\npackage y;\n");
      assertEquals(expected, SourceFileKey.of(repository.toFile(), path, "Calc"), path);
    }

    @Test
    void isTheFileNameAloneForAFileInTheDefaultPackage() throws IOException {
      write("src/main/java/com/x/A.java", "class A {}\n");
      assertEquals(
          "A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    @Test
    void isThePathBelowTheRootForAFileThatDoesNotExist() {
      assertEquals(
          "com/x/A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    @Test
    void isThePathBelowTheRootForAPathTheFileSystemCannotName() {
      assertEquals(
          "com/x/A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A\0.java", "com/x/A.java"));
    }

    @Test
    void isThePathBelowTheRootForAFileWithNoReadableDeclaration() throws IOException {
      write("src/main/java/com/x/A.java", "/* never closed\npackage demo;\n");
      assertEquals(
          "com/x/A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    @Test
    void isReadPastACommentInAnotherCharset() throws IOException {
      Files.write(
          repository.resolve("A.java"),
          "// é\npackage com.x;\n".getBytes(StandardCharsets.ISO_8859_1));
      assertEquals("com/x/A.java", SourceFileKey.of(repository.toFile(), "A.java", "A.java"));
    }
  }

  @Nested
  class TheKeyOfAPackageAndAFileName {

    @Test
    void joinsThePackagePathAndTheNameWithASlash() {
      assertEquals("com/x/A.java", SourceFileKey.of("com/x", "A.java"));
    }

    /** JaCoCo names the default package with an empty string; a class file's package is null. */
    @ParameterizedTest
    @NullAndEmptySource
    void isTheNameAloneInTheDefaultPackage(String packagePath) {
      assertEquals("A.java", SourceFileKey.of(packagePath, "A.java"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"a/B.java", "a/B.kt"})
  void javaAndKotlinSourcesAreMeasured(String path) {
    assertTrue(SourceFileKey.isSource(path), path);
  }

  @ParameterizedTest
  @ValueSource(strings = {"build.gradle.kts", "a/B.groovy", "a/B.java.txt", "a/B.class"})
  void otherFilesAreNotMeasured(String path) {
    assertFalse(SourceFileKey.isSource(path), path);
  }
}
