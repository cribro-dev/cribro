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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A git repository in a test's directory, run with the system and global configuration switched
 * off, so that the machine running the tests decides nothing.
 */
final class TestGitRepository {

  private final Path directory;

  TestGitRepository(Path directory) {
    this.directory = directory;
  }

  /** Creates the repository with one commit, on master. */
  void initWithOneCommit() throws IOException, InterruptedException {
    run("init", "--initial-branch=master", ".");
    run("config", "user.email", "test@example.com");
    run("config", "user.name", "Test");
    commit("seed");
  }

  /** Adds a file named after the message and commits everything in the tree. */
  void commit(String message) throws IOException, InterruptedException {
    Files.writeString(directory.resolve(message + ".txt"), message + "\n");
    run("add", "-A");
    run("commit", "-m", message);
  }

  /** Runs git in the repository and returns its output, failing the test where git fails. */
  String run(String... arguments) throws IOException, InterruptedException {
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
