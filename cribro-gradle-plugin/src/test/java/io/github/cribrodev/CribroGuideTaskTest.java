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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The guide writes every command the way this build has to run it, so an agent can paste the
 * command as printed.
 */
class CribroGuideTaskTest {

  @Test
  void aBuildThatAppliesCribroItselfRunsPlainGradlew() {
    assertEquals("./gradlew", CribroGuideTask.gradleCommand(null, "/home/u"));
  }

  @Test
  void anInitScriptUnderTheHomeDirectoryIsWrittenFromTilde() {
    assertEquals(
        "./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle",
        CribroGuideTask.gradleCommand("/home/u/.gradle/cribro/cribro.init.gradle", "/home/u"));
  }

  @Test
  void anInitScriptOutsideTheHomeDirectoryKeepsItsAbsolutePath() {
    assertEquals(
        "./gradlew --init-script /opt/cribro/cribro.init.gradle",
        CribroGuideTask.gradleCommand("/opt/cribro/cribro.init.gradle", "/home/u"));
  }

  /** {@code /home/u2} starts with the text {@code /home/u}, and {@code ~2} would name nothing. */
  @Test
  void aDirectoryThatSharesOnlyTheTextOfTheHomeDirectoryKeepsItsAbsolutePath() {
    assertEquals(
        "./gradlew --init-script /home/u2/cribro.init.gradle",
        CribroGuideTask.gradleCommand("/home/u2/cribro.init.gradle", "/home/u"));
  }

  /** The shell expands {@code ~/} only outside quotes, so only the rest of the path is quoted. */
  @Test
  void aSpaceUnderTheHomeDirectoryIsQuotedAfterTheTilde() {
    assertEquals(
        "./gradlew --init-script ~/'my tools/cribro.init.gradle'",
        CribroGuideTask.gradleCommand("/home/u/my tools/cribro.init.gradle", "/home/u"));
  }

  @Test
  void parenthesesAreQuoted() {
    assertEquals(
        "./gradlew --init-script '/opt/cribro(1)/cribro.init.gradle'",
        CribroGuideTask.gradleCommand("/opt/cribro(1)/cribro.init.gradle", "/home/u"));
  }

  /** In double quotes the shell would still expand {@code $HOME}. */
  @Test
  void aDollarSignIsQuotedSoTheShellExpandsNothing() {
    assertEquals(
        "./gradlew --init-script '/opt/$HOME/cribro.init.gradle'",
        CribroGuideTask.gradleCommand("/opt/$HOME/cribro.init.gradle", "/home/u"));
  }

  /** A single quote cannot appear inside single quotes, so it closes them, escapes, and reopens. */
  @Test
  void aSingleQuoteIsEscapedOutsideTheQuotes() {
    assertEquals(
        "./gradlew --init-script '/opt/it'\\''s/cribro.init.gradle'",
        CribroGuideTask.gradleCommand("/opt/it's/cribro.init.gradle", "/home/u"));
  }

  /** A backslash would need quotes in the shell, and Gradle reads {@code /} on Windows too. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aWindowsPathUnderTheHomeDirectoryIsWrittenFromTildeWithSlashes() {
    assertEquals(
        "./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle",
        CribroGuideTask.gradleCommand(
            "C:\\Users\\u\\.gradle\\cribro\\cribro.init.gradle", "C:\\Users\\u"));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aWindowsPathOutsideTheHomeDirectoryKeepsItsDriveAndUsesSlashes() {
    assertEquals(
        "./gradlew --init-script C:/opt/cribro/cribro.init.gradle",
        CribroGuideTask.gradleCommand("C:\\opt\\cribro\\cribro.init.gradle", "C:\\Users\\u"));
  }

  @Test
  void theGuideNamesTheProjectsAndFillsInTheCommandAndTheValues() {
    assertEquals(
        """
        cribro 1.2.3

        Projects with diffCoverage, changedLines, and pitestDiff: :app, :lib

        Run ./gradlew -I x.gradle <project>:test; PIT 1.30.2.
        """,
        CribroGuideTask.render(
            "Run {{gradlew}} <project>:test; PIT {{pitestVersion}}.\n",
            "1.2.3",
            "./gradlew -I x.gradle",
            List.of(":lib", ":app"),
            Map.of("pitestVersion", "1.30.2")));
  }

  @Test
  void aBuildWithNoJavaProjectIsToldNoProjectHasTheTasks() {
    assertEquals(
        """
        cribro 1.2.3

        No project of this build applies the java plugin, so no project has the tasks.

        Run ./gradlew.
        """,
        CribroGuideTask.render(
            "Run {{gradlew}}.\n", "1.2.3", "./gradlew", List.of(), Map.of()));
  }
}
