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
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The PIT command line both plugins start PIT with. */
class PitestRunTest {

  @TempDir Path directory;

  private PitestRun pit(List<File> classPath) {
    return new PitestRun()
        .targetClasses(List.of("demo.Calc"))
        .reportDir(directory.resolve("report").toFile())
        .changedLines(directory.resolve("changed-lines.txt").toFile())
        .sourceEncoding("UTF-8")
        .classPath(classPath);
  }

  private List<String> arguments(List<File> classPath) throws IOException {
    return pit(classPath).arguments();
  }

  /** Returns the value that follows {@code --name}, or null where the option is absent. */
  private static String option(List<String> arguments, String name) {
    int index = arguments.indexOf("--" + name);
    return index < 0 ? null : arguments.get(index + 1);
  }

  /**
   * PIT's default puts its own dependencies, such as commons-lang3, ahead of the tests' classpath,
   * so a project that has a class of the same name gets PIT's copy mutated and tested.
   */
  @Test
  void pitLeavesItsOwnLaunchClasspathOffTheTestsClasspath() throws IOException {
    assertEquals("false", option(arguments(List.of()), "includeLaunchClasspath"));
  }

  @Test
  void aRunWithNoTestGlobNamesEveryTestByARegularExpression() throws IOException {
    assertEquals(
        "~.*",
        option(arguments(List.of()), "targetTests"),
        "targetTests with no glob given; the glob * is expanded into file names by the Java"
            + " launcher on Windows");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" , "})
  void aPluginsPropertyWithNoCoordinateNamesNoPlugin(String property) {
    assertEquals(List.of(), PitestRun.plugins(property));
  }

  @Test
  void thePluginsPropertyIsSplitAtCommasTrimmedAndStrippedOfBlanks() {
    assertEquals(List.of("a:b:1", "c:d:2"), PitestRun.plugins(" a:b:1 , ,c:d:2 "));
  }

  /** The source root holds no demo/math directory, so only the changed-lines file finds Calc.kt. */
  @Test
  void theReportQuotesAMutatedFileFromThePathTheChangedLinesFileNames() throws IOException {
    File changedLines = directory.resolve("changed-lines.txt").toFile();
    ChangedLinesFile.write(
        changedLines.toPath(),
        List.of("against origin/master (abc)"),
        List.of(
            new ChangedFile(
                "src/main/kotlin/Calc.kt", "demo/math/Calc.kt", new TreeSet<>(List.of(3)))));
    Path source = directory.resolve("src/main/kotlin/Calc.kt");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo.math\n\nfun two() = 2\n");
    Path reportDir = directory.resolve("report");
    Files.createDirectories(reportDir);
    Files.writeString(
        reportDir.resolve("mutants.tsv"),
        "SURVIVED\tdemo.math.CalcKt\ttwo\tCalc.kt\t3\treplaced int return with 0"
            + " for demo/math/CalcKt::two\n");

    String report =
        PitestRun.report(
            changedLines,
            reportDir.toFile(),
            null,
            directory.toFile(),
            List.of(directory.resolve("src/main/kotlin").toFile()),
            10,
            10,
            10,
            false);

    assertLinesMatch(
        List.of(
            ">> the header >>",
            "  src/main/kotlin/Calc.kt",
            "    3: fun two() = 2",
            ">> the rest >>"),
        report.lines().toList());
  }

  /** The file keeps a long classpath off a command line that Windows limits to 32767 characters. */
  @Test
  void theTestsClasspathReachesPitThroughAFileInItsOrder() throws IOException {
    File classes = directory.resolve("classes").toFile();
    File withComma = directory.resolve("lib,1.jar").toFile();

    List<String> arguments = arguments(List.of(classes, withComma));

    assertEquals(
        List.of(classes.getAbsolutePath(), withComma.getAbsolutePath()),
        Files.readAllLines(Path.of(option(arguments, "classPathFile"))));
  }

  /** A path outside ASCII has to reach PIT as written, whatever the platform's charset is. */
  @Test
  void pitStartsInTheCharsetItsClasspathFileIsWrittenIn() throws IOException {
    File jar = directory.resolve("lib-\u00fc\u4e2d.jar").toFile();
    PitestRun pit = pit(List.of(jar));

    List<String> arguments = pit.arguments();

    Charset charset = Charset.forName(pit.systemProperties().get("file.encoding"));
    assertEquals(
        List.of(jar.getAbsolutePath()),
        Files.readAllLines(Path.of(option(arguments, "classPathFile")), charset));
  }

  /** The JVM's default charset is the classpath file's, so the sources' own has to be named. */
  @Test
  void pitReadsTheSourcesInTheBuildsEncoding() throws IOException {
    List<String> arguments = pit(List.of()).sourceEncoding("ISO-8859-1").arguments();

    assertEquals("ISO-8859-1", option(arguments, "inputEncoding"));
  }

  @Test
  void aRunWithoutASourceEncodingIsRefused() {
    PitestRun pit = pit(List.of()).sourceEncoding(null);

    assertThrows(IllegalStateException.class, pit::arguments);
  }

  @Test
  void pitsFilterGetsTheChangedLinesFile() {
    File changedLines = directory.resolve("changed-lines.txt").toFile();

    assertEquals(
        changedLines.getAbsolutePath(),
        pit(List.of()).systemProperties().get(ChangedLinesFile.PITEST_PROPERTY));
  }
}
