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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs git as a child process in one repository, once per argument list.
 *
 * <p>Every command and every diff is kept for the life of the instance, so that the reports of one
 * build measure one state of the repository however many modules ask. Gradle keeps one instance in
 * a build service and Maven one in the session's data, so a build runs each command once and parses
 * each diff once. Instances are safe for concurrent use.
 */
public final class Git {

  /** What one git command printed. */
  private static final class Output {
    /** The trimmed standard output, or null where git reported failure. */
    final String value;

    /** The trimmed standard error, which is what a failure has to report. */
    final String error;

    Output(String value, String error) {
      this.value = value;
      this.error = error;
    }
  }

  private final File repositoryRoot;
  private final Map<List<String>, Output> outputs = new ConcurrentHashMap<>();
  private final Map<Optional<String>, Optional<ChangedLines>> diffs = new ConcurrentHashMap<>();

  public Git(File repositoryRoot) {
    this.repositoryRoot = repositoryRoot;
  }

  /**
   * Returns the nearest directory at or above {@code start} that holds {@code .git}, which git
   * prints the paths of a diff against, or null where none does.
   */
  public static File findRepositoryRoot(File start) {
    for (File dir = start.getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
      if (new File(dir, ".git").exists()) {
        return dir;
      }
    }
    return null;
  }

  /** Returns the repository the commands run in, which is also where its files are read from. */
  public File repositoryRoot() {
    return repositoryRoot;
  }

  /**
   * Returns the trimmed output of a git command.
   *
   * @throws CribroException where git reported failure, with what git printed on standard error,
   *     since empty output means the command succeeded and found nothing, and the two must not read
   *     alike; and where git cannot be started at all
   */
  public String output(String... arguments) {
    Output result = run(arguments);
    if (result.value == null) {
      throw new CribroException("git " + String.join(" ", arguments) + " failed: " + result.error);
    }
    return result.value;
  }

  /**
   * Returns the trimmed output of a git command, or null where git reported failure.
   *
   * @throws CribroException where git cannot be started, which no ref or setting would repair
   */
  public String outputOrNull(String... arguments) {
    return run(arguments).value;
  }

  /**
   * Returns what {@link ChangedLines#measure} returns for the base ref, measured on the first call
   * for that ref.
   *
   * @param baseRef the ref the caller named, or null to try the defaults
   * @throws CribroException where the explicit ref does not resolve
   */
  public ChangedLines changedLines(String baseRef) {
    return diffs
        .computeIfAbsent(
            Optional.ofNullable(baseRef),
            ref -> Optional.ofNullable(ChangedLines.measure(this, ref.orElse(null))))
        .orElse(null);
  }

  private Output run(String... arguments) {
    return outputs.computeIfAbsent(List.of(arguments), this::execute);
  }

  private Output execute(List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(arguments);
    try {
      Process process = new ProcessBuilder(command).directory(repositoryRoot).start();
      process.getOutputStream().close();
      // Both streams are drained at once: a child that fills one pipe while nobody reads it blocks.
      CompletableFuture<String> error =
          CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
      String output = read(process.getInputStream());
      int exitCode = process.waitFor();
      return new Output(exitCode == 0 ? output : null, error.join());
    } catch (IOException e) {
      throw new CribroException("cannot run git in " + repositoryRoot + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Output(null, "interrupted");
    }
  }

  private static String read(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
