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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every task of a build gets the same {@link Git}, so a git command runs once a build.
 *
 * <p>The cache is what makes the service worth registering: twelve modules ask the same questions
 * of one repository, and a second run of a command can only repeat its answer. It is asserted by
 * changing the repository between two identical calls through the registered service, since a call
 * that ran again would report the new state.
 *
 * <p>Every command runs against a repository built here. The fixture that builds it switches the
 * system and global configuration off for its own processes, so that a setting on the machine
 * running the tests decides nothing about what the repository holds.
 */
class GitServiceTest {

  @TempDir Path directory;

  private GitService service() {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    return project
        .getGradle()
        .getSharedServices()
        .registerIfAbsent(
            "git",
            GitService.class,
            service -> service.getParameters().getRepositoryRoot().set(directory.toFile()))
        .get();
  }

  @Test
  void aRepeatedCommandKeepsTheAnswerOfItsFirstRunForTheBuild() throws Exception {
    repositoryWithOneCommit();
    GitService service = service();
    String first = service.git().output("rev-parse", "HEAD");

    commit("second");

    assertEquals(
        first,
        service.git().output("rev-parse", "HEAD"),
        "rev-parse HEAD after a second commit");
    assertNotEquals(first, head(), "the commit HEAD names after the second commit");
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

  /** Returns the commit HEAD names now, read outside the service so no cache stands between. */
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
    // Read before waiting: nothing else drains the pipe, so a command that filled it would hang,
    // and what git printed is the only account of why it failed.
    String output;
    try (InputStream stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), UTF_8);
    }
    assertEquals(0, process.waitFor(), () -> "git " + String.join(" ", arguments) + ": " + output);
    return output;
  }
}
