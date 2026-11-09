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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One git command runs, and one diff is measured, once per instance, and a failing command is told
 * apart from an empty result.
 *
 * <p>The cache is asserted by changing the repository between two identical calls, since a call
 * that ran again would report the new state. The repository is built here with the system and
 * global configuration switched off, so that the machine running the tests decides nothing.
 */
class GitTest {

  @TempDir Path directory;

  @Test
  void aRepeatedCommandKeepsTheAnswerOfItsFirstRun() throws Exception {
    repositoryWithOneCommit();
    Git git = new Git(directory.toFile());
    String first = git.output("rev-parse", "HEAD");

    commit("second");

    assertEquals(first, git.output("rev-parse", "HEAD"), "rev-parse HEAD after a second commit");
    assertNotEquals(first, head(), "the commit HEAD names after the second commit");
  }

  @Test
  void aCommandIsKeyedByItsWholeArgumentList() throws Exception {
    repositoryWithOneCommit();
    Git git = new Git(directory.toFile());
    git.output("rev-parse", "HEAD");

    commit("second");

    assertEquals(head(), git.output("rev-parse", "master"), "rev-parse master");
  }

  @Test
  void aFailingCommandComesBackAsNullRatherThanAsEmptyOutput() throws Exception {
    repositoryWithOneCommit();

    assertNull(
        new Git(directory.toFile())
            .outputOrNull("rev-parse", "--verify", "--quiet", "absent^{commit}"),
        "a ref that does not resolve");
  }

  @Test
  void aFailingCommandReportsWhatGitPrinted() throws Exception {
    repositoryWithOneCommit();
    Git git = new Git(directory.toFile());

    CribroException thrown =
        assertThrows(CribroException.class, () -> git.output("rev-parse", "absent"));

    String prefix = "git rev-parse absent failed: ";
    assertTrue(
        thrown.getMessage().startsWith(prefix),
        () -> "the command that failed: " + thrown.getMessage());
    assertNotEquals(
        "",
        thrown.getMessage().substring(prefix.length()),
        () -> "what git printed about the ref: " + thrown.getMessage());
  }

  @Test
  void aCommandThatFoundNothingComesBackAsEmptyOutput() throws Exception {
    repositoryWithOneCommit();

    assertEquals(
        "",
        new Git(directory.toFile())
            .output("ls-files", "--others", "--exclude-standard", "--", "*.java"),
        "an untracked Java file");
  }

  @Test
  void aFailedCommandKeepsFailingForTheLifeOfTheInstance() throws Exception {
    repositoryWithOneCommit();
    Git git = new Git(directory.toFile());
    String[] resolveAbsent = {"rev-parse", "--verify", "--quiet", "absent^{commit}"};
    git.outputOrNull(resolveAbsent);

    run("branch", "absent");

    assertNull(git.outputOrNull(resolveAbsent), "the ref the branch now names");
  }

  /**
   * The lines of an untracked file are read from disk rather than from git, so only the diff cache
   * keeps a second call from seeing lines written after the first.
   */
  @Test
  void aDiffIsMeasuredOncePerBaseRef() throws Exception {
    repositoryWithOneCommit();
    Path added = directory.resolve("Added.java");
    Files.writeString(added, "class Added {}\n");
    Git git = new Git(directory.toFile());
    git.changedLines("master");

    Files.writeString(added, "class Added {\n}\n");

    assertEquals(
        Set.of(1),
        git.changedLines("master").lines().get("Added.java"),
        "changed lines of Added.java, measured again after a line was added to it");
    assertEquals(
        Set.of(1, 2),
        new Git(directory.toFile()).changedLines("master").lines().get("Added.java"),
        "changed lines of Added.java, measured by a new instance");
  }

  @Test
  void aGitThatCannotStartFailsRatherThanReportingFailure() {
    Git git = new Git(directory.resolve("absent").toFile());

    CribroException e = assertThrows(CribroException.class, () -> git.outputOrNull("status"));
    assertTrue(
        e.getMessage().startsWith("cannot run git in " + directory.resolve("absent")),
        () -> "message: " + e.getMessage());
  }

  @Test
  void aDirectoryInsideARepositoryFindsTheRepositoryAboveIt() throws Exception {
    Files.createDirectory(directory.resolve(".git"));
    Path module = Files.createDirectories(directory.resolve("parent/module"));

    assertEquals(directory.toFile(), Git.findRepositoryRoot(module.toFile()), "root of parent/module");
  }

  @Test
  void theNearestRepositoryWinsOverOneFurtherUp() throws Exception {
    Files.createDirectory(directory.resolve(".git"));
    Path nested = Files.createDirectories(directory.resolve("nested"));
    // A linked worktree or a submodule marks its root with a .git file rather than a directory.
    Files.writeString(nested.resolve(".git"), "gitdir: elsewhere\n");
    Path module = Files.createDirectories(nested.resolve("module"));

    assertEquals(nested.toFile(), Git.findRepositoryRoot(module.toFile()), "root of nested/module");
  }

  @Test
  void aRelativeStartIsResolvedBeforeTheWalk() throws Exception {
    File relative = new File("");

    assertEquals(
        Git.findRepositoryRoot(relative.getAbsoluteFile()),
        Git.findRepositoryRoot(relative),
        "root of the working directory, given as an empty relative path");
  }

  /** Creates a repository whose only commit is on master. */
  private void repositoryWithOneCommit() throws Exception {
    run("init", "--initial-branch=master", ".");
    run("config", "user.email", "test@example.com");
    run("config", "user.name", "Test");
    commit("seed");
  }

  /** Adds a file named after the message and commits everything in the tree. */
  private void commit(String message) throws Exception {
    Files.writeString(directory.resolve(message + ".txt"), message + "\n");
    run("add", "-A");
    run("commit", "-m", message);
  }

  /** Returns the commit HEAD names now, read outside the instance so no cache stands between. */
  private String head() throws Exception {
    return run("rev-parse", "HEAD").trim();
  }

  private String run(String... arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    // A path git can read and will not find, rather than /dev/null, which names a file of its own
    // on Windows and would leave the configuration of the machine deciding what these tests see.
    String noConfig = directory.resolve("no-git-config").toString();
    environment.put("GIT_CONFIG_GLOBAL", noConfig);
    environment.put("GIT_CONFIG_SYSTEM", noConfig);
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    // git commit starts a detached git maintenance run --auto, which can still be writing under
    // .git after the commit exits, and JUnit then fails to delete the temporary directory.
    environment.put("GIT_CONFIG_COUNT", "1");
    environment.put("GIT_CONFIG_KEY_0", "maintenance.auto");
    environment.put("GIT_CONFIG_VALUE_0", "false");
    Process process = builder.start();
    // Read before waiting: nothing else drains the pipe, so a command that filled it would hang.
    String output;
    try (InputStream stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), UTF_8);
    }
    assertEquals(0, process.waitFor(), () -> "git " + String.join(" ", arguments) + ": " + output);
    return output;
  }
}
