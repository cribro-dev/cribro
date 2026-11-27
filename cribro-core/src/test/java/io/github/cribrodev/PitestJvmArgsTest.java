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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pitest.mutationtest.commandline.CommaAwareArgsProcessor;

/**
 * The test JVM under PIT gets the JVM arguments, the system properties, and the heap limit of the
 * Gradle test task.
 *
 * <p>The system properties used to be left out: Gradle moves a {@code -D} given through {@code
 * jvmArgs} into the system properties, and the jmh tests that read their source directories from
 * such properties failed under PIT and were skipped.
 */
class PitestJvmArgsTest {

  /** Returns the arguments that the PIT on the test classpath makes of a {@code --jvmArgs} value. */
  private static List<String> pitSplit(String value) {
    OptionParser parser = new OptionParser();
    OptionSpec<String> jvmArgs = parser.accepts("jvmArgs").withRequiredArg();
    return new CommaAwareArgsProcessor(jvmArgs).values(parser.parse("--jvmArgs", value));
  }

  @Test
  void systemPropertiesReachTheTestJvm() {
    assertEquals(
        "--add-opens=a/b=ALL-UNNAMED,-Dsources=/tmp/src,-Xmx1g",
        PitestJvmArgs.of(List.of("--add-opens=a/b=ALL-UNNAMED"), Map.of("sources", "/tmp/src"), "1g")
            .value());
  }

  @Test
  void aTaskWithNoHeapLimitPassesNone() {
    assertEquals("-Da=1", PitestJvmArgs.of(List.of(), Map.of("a", 1), null).value());
  }

  @Test
  void anArgumentWithACommaReachesTheTestJvmWhole() {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-ea"), Map.of("packages", "a,b"), null);
    assertAll(
        () -> assertEquals(List.of("-ea", "-Dpackages=a,b"), pitSplit(args.value())),
        () -> assertEquals(List.of(), args.leftOut(), "leftOut"));
  }

  /** PIT would remove the argument's own braces and pass {@code -Dpattern=a,b}. */
  @ParameterizedTest
  @ValueSource(strings = {"{a,b}", "a,b}"})
  void anArgumentWithACommaAndABraceIsLeftOut(String pattern) {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-ea"), Map.of("pattern", pattern), null);
    assertAll(
        () -> assertEquals(List.of("-ea"), pitSplit(args.value())),
        () -> assertEquals(List.of("-Dpattern=" + pattern), args.leftOut(), "leftOut"));
  }

  /** PIT removes the braces of every argument once it finds a comma inside braces. */
  @ParameterizedTest
  @ValueSource(strings = {"-Dx={y}", "-Dd=}"})
  void aBraceInAnotherArgumentLeavesAnArgumentWithACommaOut(String braced) {
    PitestJvmArgs args = PitestJvmArgs.of(List.of(braced), Map.of("packages", "a,b"), null);
    assertAll(
        () -> assertEquals(List.of(braced), pitSplit(args.value())),
        () -> assertEquals(List.of("-Dpackages=a,b"), args.leftOut(), "leftOut"));
  }

  @Test
  void anArgumentWithACommaAndWhitespaceIsLeftOut() {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-ea"), Map.of("packages", "a, b"), null);
    assertAll(
        () -> assertEquals(List.of("-ea"), pitSplit(args.value())),
        () -> assertEquals(List.of("-Dpackages=a, b"), args.leftOut(), "leftOut"));
  }

  /**
   * pgjdbc sets {@code junit.jupiter.execution.timeout.default=5 m}; passed on, the minion JVM read
   * {@code m} as its main class and PIT failed before any mutant ran.
   */
  @Test
  void anArgumentWithWhitespaceIsLeftOut() {
    PitestJvmArgs args =
        PitestJvmArgs.of(List.of(), Map.of("junit.jupiter.execution.timeout.default", "5 m"), null);
    assertEquals("", args.value());
    assertEquals(List.of("-Djunit.jupiter.execution.timeout.default=5 m"), args.leftOut());
  }

  /**
   * The {@code java} launcher drops the quotes of a line in an argument file and reads what follows
   * a {@code #} as a comment.
   */
  @Test
  void anArgumentWithAQuoteOrAHashIsLeftOut() {
    List<String> changed = List.of("-Dq=\"x\"", "-Dq='x'", "-Dh=a#b", "-Dh=a#b,c");
    PitestJvmArgs args = PitestJvmArgs.of(changed, Map.of(), null);
    assertAll(
        () -> assertEquals("", args.value(), "value"),
        () -> assertEquals(changed, args.leftOut(), "leftOut"));
  }

  /** The launcher treats a backslash outside quotes as an ordinary character. */
  @Test
  void anArgumentWithABackslashIsPassed() {
    PitestJvmArgs args =
        PitestJvmArgs.of(List.of("-Dd=C:\\dir\\x", "-Dp=a\\b,c"), Map.of(), null);
    assertAll(
        () -> assertEquals(List.of("-Dd=C:\\dir\\x", "-Dp=a\\b,c"), pitSplit(args.value())),
        () -> assertEquals(List.of(), args.leftOut(), "leftOut"));
  }

  /** PIT would read the comma after the open brace as inside it and join the heap limit to it. */
  @Test
  void anArgumentThatLeavesABraceOpenIsLeftOut() {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-Dx={"), Map.of(), "1g");
    assertAll(
        () -> assertEquals(List.of("-Xmx1g"), pitSplit(args.value())),
        () -> assertEquals(List.of("-Dx={"), args.leftOut(), "leftOut"));
  }

  @Test
  void everyPassedArgumentComesOutOfPitsSplitAsItWent() {
    List<String> arguments =
        List.of(
            "-ea", "-Da={b}", "-Dc=}{", "-Dd=}", "-De={", "-Df=g{h}i", "-Dj=a,b", "-Dk=@x",
            "-Xmx1g");
    PitestJvmArgs args = PitestJvmArgs.of(arguments, Map.of(), null);
    List<String> passed = new ArrayList<>(arguments);
    passed.removeAll(args.leftOut());
    assertEquals(passed, pitSplit(args.value()), "leftOut " + args.leftOut());
  }

  /** A brace in an argument that is left out anyway does not keep a comma out of braces. */
  @Test
  void aBraceInALeftOutArgumentStillLetsACommaThrough() {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-Dx={\"y\"}"), Map.of("p", "a,b"), null);
    assertAll(
        () -> assertEquals(List.of("-Dp=a,b"), pitSplit(args.value())),
        () -> assertEquals(List.of("-Dx={\"y\"}"), args.leftOut(), "leftOut"));
  }
}
