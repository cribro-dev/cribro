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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Kotlin build is measured as a Java build is: the sources under {@code src/main/kotlin} count,
 * and a file is matched to its JaCoCo counters and its PIT mutants by the package it declares,
 * which in Kotlin need not be the directory it lies in.
 *
 * <p>The build applies the Kotlin JVM plugin from the Plugin Portal and resolves Kotlin and JUnit
 * from Maven Central, so the test needs the network, as the Maven integration tests do.
 */
class KotlinBuildTest {

  /** A stable Kotlin release whose Gradle plugin runs on the Gradle version TestKit starts. */
  static final String KOTLIN_VERSION = "2.4.21";

  @TempDir Path build;

  /**
   * Writes a one-file Kotlin build whose only test covers one branch of {@code max} and never calls
   * {@code next} or {@code log}. The file lies at the root of {@code src/main/kotlin} and declares a
   * package, so its directory does not follow it. The closing brace of {@code log} carries the
   * method's return instruction and holds no code.
   */
  private void writeBuild() throws IOException {
    Files.writeString(
        build.resolve("settings.gradle"),
        "plugins { id '" + CribroSettingsPlugin.ID + "' }\nrootProject.name = 'demo'\n",
        UTF_8);
    Files.writeString(
        build.resolve("build.gradle"),
        """
        plugins { id 'org.jetbrains.kotlin.jvm' version '%s' }
        repositories { mavenCentral() }
        dependencies {
            testImplementation platform('org.junit:junit-bom:5.14.4')
            testImplementation 'org.junit.jupiter:junit-jupiter'
            testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
        }
        kotlin { jvmToolchain(%d) }
        test { useJUnitPlatform() }
        """
            .formatted(KOTLIN_VERSION, Runtime.version().feature()),
        UTF_8);
    write(
        "src/main/kotlin/Calc.kt",
        """
        package demo.math

        fun max(a: Int, b: Int): Int {
            if (a > b) {
                return a
            }
            return b
        }

        fun next(a: Int): Int {
            return a + 1
        }

        fun log(a: Int) {
            println(a)
        }
        """);
    write(
        "src/test/kotlin/demo/math/CalcTest.kt",
        """
        package demo.math

        import org.junit.jupiter.api.Assertions.assertEquals
        import org.junit.jupiter.api.Test

        class CalcTest {
            @Test
            fun maxOfTwoAndOneIsTwo() {
                assertEquals(2, max(2, 1))
            }
        }
        """);
  }

  private void write(String path, String text) throws IOException {
    Path file = build.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, UTF_8);
  }

  /** Commits an empty base and then the whole build, so every line of it is a changed line. */
  private void commitAsChange() throws IOException, InterruptedException {
    git("init", "-q", "--initial-branch=main");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "base");
    git("add", "-A");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "change");
  }

  private void git(String... arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(build.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    assertEquals(0, process.waitFor(), () -> String.join(" ", command) + "\n" + output);
  }

  @Test
  void aKotlinFileIsMeasuredByThePackageItDeclaresRatherThanItsDirectory() throws Exception {
    writeBuild();
    commitAsChange();

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(build.toFile())
            .withPluginClasspath()
            .withArguments("--quiet", "test", "pitestDiff", "-Pcribro.diffBase=HEAD~1")
            .build();

    String output = result.getOutput();
    assertAll(
        () -> assertContains(output, "  src/main/kotlin/Calc.kt\n    "),
        () -> assertContains(output, "    2/5 changed lines executed, 1/2 branches covered"),
        () -> assertContains(output, "      4: 1 of 2 branches covered: if (a > b) {"),
        () -> assertContains(output, "      7: not executed: return b"),
        () -> assertContains(output, "      11: not executed: return a + 1"),
        () -> assertContains(output, "      15: not executed: println(a)"),
        () -> assertContains(output, " of 5 changed lines: "),
        () ->
            assertEquals(
                number(output, "Total: \\d+/(\\d+) changed lines executed"),
                number(output, "mutants? on \\d+ of (\\d+) changed lines: "),
                "changed lines in the mutation summary, which has to leave out the brace lines that"
                    + " the diff coverage total leaves out"),
        () -> assertContains(output, "survived: changed conditional boundary, in max"),
        () -> assertContains(output, "no test reaches it: Replaced integer addition with subtraction, in next"));
  }

  /** Returns the number the first group of {@code regex} captures in the output. */
  private static int number(String output, String regex) {
    Matcher matcher = Pattern.compile(regex).matcher(output);
    assertTrue(matcher.find(), () -> "expected a match of " + regex + " in:\n" + output);
    return Integer.parseInt(matcher.group(1));
  }

  private static void assertContains(String output, String expected) {
    assertTrue(output.contains(expected), () -> "expected:\n" + expected + "\nin:\n" + output);
  }
}
