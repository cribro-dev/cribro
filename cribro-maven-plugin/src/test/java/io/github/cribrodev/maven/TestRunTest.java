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
package io.github.cribrodev.maven;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which execution data belongs to one run of a module's tests. */
class TestRunTest {

  @TempDir Path directory;

  @Test
  void aForkNumberPlaceholderStandsForTheFileOfEachFork() throws IOException {
    for (String name : List.of("jacoco-1.exec", "jacoco-12.exec", "jacoco-x.exec", "other.exec")) {
      Files.createFile(directory.resolve(name));
    }
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory.resolve("jacoco-${surefire.forkNumber}.exec").toFile());
    assertEquals(
        List.of("jacoco-1.exec", "jacoco-12.exec"),
        run.execFiles().stream().map(File::getName).sorted().collect(Collectors.toList()));
  }

  @Test
  void onlyASessionStartedWhileTheTestsRanCounts() {
    TestRun run = TestRun.measured(new MavenProject(), 1000, null, null, new File("jacoco.exec"));
    run.end = 2000;
    assertFalse(run.contains(999), "a session of an earlier build, appended to the same file");
    assertTrue(run.contains(1000), "start");
    assertTrue(run.contains(2000), "end");
    assertFalse(run.contains(2001), "a session of failsafe, which runs after surefire");
  }

  private static Properties userProperties(String name, String value) {
    Properties properties = new Properties();
    properties.setProperty(name, value);
    return properties;
  }

  /** A POM that always sets groups runs the module's whole suite on every mvn test. */
  @Test
  void groupsThePomSetsAreNotTheUsers() {
    assertNull(TestRun.passedByUser(new Properties(), "groups", "fast"));
  }

  @Test
  void groupsTheUserPassesAreTheUsers() {
    assertEquals("fast", TestRun.passedByUser(userProperties("groups", "fast"), "groups", "fast"));
  }

  @Test
  void aTestFilterTheUserPassesIsTheUsers() {
    assertEquals(
        "FooTest", TestRun.passedByUser(userProperties("test", "FooTest"), "test", "FooTest"));
  }

  @Test
  void aTestFilterThePomSetsIsNotTheUsers() {
    assertNull(TestRun.passedByUser(new Properties(), "test", "FooTest"));
  }

  /** Each flag is read on its own, so -Dtest does not make the POM's groups the user's. */
  @Test
  void aTestFilterTheUserPassesLeavesThePomsGroupsThePoms() {
    Properties user = userProperties("test", "FooTest");

    assertNull(TestRun.passedByUser(user, "groups", "fast"));
  }

  @Test
  void aRunIsFilteredByEitherFilterTheUserPassed() {
    File exec = new File("jacoco.exec");
    assertAll(
        () -> assertFalse(TestRun.measured(new MavenProject(), 0, null, null, exec).filtered, "none"),
        () -> assertTrue(TestRun.measured(new MavenProject(), 0, "FooTest", null, exec).filtered, "-Dtest"),
        () -> assertTrue(TestRun.measured(new MavenProject(), 0, null, "fast", exec).filtered, "-Dgroups"));
  }

  /** Surefire replaces the placeholder anywhere in the argLine, directories included. */
  @Test
  void aPlaceholderInADirectoryStandsForTheDirectoryOfEachFork() throws IOException {
    for (String path : List.of("1/jacoco.exec", "12/jacoco.exec", "x/jacoco.exec", "1/other.exec")) {
      Files.createDirectories(directory.resolve(path).getParent());
      Files.createFile(directory.resolve(path));
    }
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory.resolve("${surefire.forkNumber}/jacoco.exec").toFile());

    assertEquals(
        List.of("1/jacoco.exec", "12/jacoco.exec"),
        run.execFiles().stream()
            .map(file -> directory.relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
            .sorted()
            .collect(Collectors.toList()));
  }

  @Test
  void aPlaceholderInADirectoryAndInTheNameMatchesBoth() throws IOException {
    for (String path : List.of("1/jacoco-1.exec", "2/jacoco-2.exec", "x/jacoco-1.exec", "1/jacoco-x.exec")) {
      Files.createDirectories(directory.resolve(path).getParent());
      Files.createFile(directory.resolve(path));
    }
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory
                .resolve("${surefire.forkNumber}/jacoco-${surefire.forkNumber}.exec")
                .toFile());

    assertEquals(
        List.of("1/jacoco-1.exec", "2/jacoco-2.exec"),
        run.execFiles().stream()
            .map(file -> directory.relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
            .sorted()
            .collect(Collectors.toList()));
  }

  @Test
  void aThreadNumberPlaceholderStandsForTheFileOfEachForkToo() throws IOException {
    Files.createFile(directory.resolve("jacoco-3.exec"));
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory.resolve("jacoco-${surefire.threadNumber}.exec").toFile());

    assertEquals(List.of(directory.resolve("jacoco-3.exec").toFile()), run.execFiles());
  }

  /** A build that keeps {@code target} on another disk links to it, and the agent writes there. */
  @Test
  void aLinkedDirectoryIsFollowed() throws IOException {
    Path real = Files.createDirectories(directory.resolve("real"));
    Files.createFile(real.resolve("jacoco.exec"));
    Path target = link(directory.resolve("target"), real);
    TestRun run =
        TestRun.measured(new MavenProject(), 0, null, null, target.resolve("jacoco.exec").toFile());

    assertEquals(List.of(target.resolve("jacoco.exec").toFile()), run.execFiles());
  }

  @Test
  void aLinkedForkDirectoryIsFollowed() throws IOException {
    Files.createDirectories(directory.resolve("1"));
    Files.createFile(directory.resolve("1/jacoco.exec"));
    Path elsewhere = Files.createDirectories(directory.resolve("elsewhere"));
    Files.createFile(elsewhere.resolve("jacoco.exec"));
    link(directory.resolve("2"), elsewhere);
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory.resolve("${surefire.forkNumber}/jacoco.exec").toFile());

    assertEquals(
        List.of("1/jacoco.exec", "2/jacoco.exec"),
        run.execFiles().stream()
            .map(file -> directory.relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
            .sorted()
            .collect(Collectors.toList()));
  }

  /** An unreadable directory beside the forks' ones, such as another tool's output, skips no fork. */
  @Test
  void aDirectoryThatCannotBeReadLeavesTheOthersFound() throws IOException {
    List<String> expected = new ArrayList<>();
    for (int fork = 1; fork <= 10; fork++) {
      Path file = Files.createDirectories(directory.resolve(String.valueOf(fork))).resolve("jacoco.exec");
      Files.createFile(file);
      expected.add(fork + "/jacoco.exec");
    }
    Path locked = Files.createDirectories(directory.resolve("locked"));
    Files.createFile(locked.resolve("jacoco.exec"));
    assumeTrue(locked.toFile().setReadable(false), "a file system with permissions");
    try {
      assumeFalse(locked.toFile().canRead(), "a user the permissions apply to");
      TestRun run =
          TestRun.measured(
              new MavenProject(),
              0,
              null,
              null,
              directory.resolve("${surefire.forkNumber}/jacoco.exec").toFile());

      assertEquals(
          expected.stream().sorted().collect(Collectors.toList()),
          run.execFiles().stream()
              .map(file -> directory.relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
              .sorted()
              .collect(Collectors.toList()));
    } finally {
      locked.toFile().setReadable(true);
    }
  }

  /** Creates a symbolic link, or skips the test where the user may not, as on Windows by default. */
  private static Path link(Path link, Path target) throws IOException {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | FileSystemException e) {
      abort("a file system that allows symbolic links: " + e);
      throw e;
    }
  }

  /** The agent has not written yet where its directory is missing, as before the tests start. */
  @Test
  void aMissingDirectoryHoldsNoFile() {
    TestRun run =
        TestRun.measured(
            new MavenProject(),
            0,
            null,
            null,
            directory.resolve("absent/${surefire.forkNumber}/jacoco.exec").toFile());

    assertEquals(List.of(), run.execFiles());
  }
}
