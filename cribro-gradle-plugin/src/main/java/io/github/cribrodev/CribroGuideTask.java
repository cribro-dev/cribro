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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Prints the guide to diff coverage and diff mutation testing, with the commands written the way
 * this build has to run them and the projects that have the tasks.
 *
 * <p>The guide ships in the plugin jar, so it describes the tasks and properties of the version
 * that prints it. An agent's skill or a project's {@code AGENTS.md} can then point at this task
 * instead of copying the guide.
 *
 * <p>The output goes to the {@code QUIET} log level, which {@code --quiet} still shows.
 */
@UntrackedTask(because = "it only prints")
public abstract class CribroGuideTask extends DefaultTask {

  /** The cribro version, printed in the first line. */
  @Internal
  public abstract Property<String> getVersion();

  /** The command that runs Gradle with cribro, such as {@code ./gradlew --init-script <path>}. */
  @Internal
  public abstract Property<String> getGradleCommand();

  /** Paths of the projects that apply the {@code java} plugin, and so have the tasks, in any order. */
  @Internal
  public abstract ListProperty<String> getProjectPaths();

  @TaskAction
  void print() {
    getLogger()
        .quiet(
            render(
                guide(),
                getVersion().get(),
                getGradleCommand().get(),
                getProjectPaths().get(),
                Map.of(
                    "pitestVersion", CribroPlugin.pitestVersion(),
                    "jacocoVersion", CribroPlugin.JACOCO_VERSION)));
  }

  /**
   * Returns the guide for this build.
   *
   * @param template the guide, with {@code {{gradlew}}} where the Gradle command goes and {@code
   *     {{name}}} for each entry of {@code values}
   */
  static String render(
      String template,
      String version,
      String gradleCommand,
      List<String> projectPaths,
      Map<String, String> values) {
    StringBuilder out = new StringBuilder();
    out.append("cribro ").append(version).append("\n\n");
    if (projectPaths.isEmpty()) {
      out.append("No project of this build applies the java plugin, so no project has the tasks.\n");
    } else {
      out.append("Projects with diffCoverage, changedLines, and pitestDiff: ")
          .append(projectPaths.stream().sorted().collect(Collectors.joining(", ")))
          .append("\n");
    }
    out.append('\n');
    String guide = template.replace("{{gradlew}}", gradleCommand);
    for (Map.Entry<String, String> value : values.entrySet()) {
      guide = guide.replace("{{" + value.getKey() + "}}", value.getValue());
    }
    return out.append(guide).toString();
  }

  /**
   * Returns the command that runs Gradle with cribro applied.
   *
   * @param initScript the init script that applied cribro, or {@code null} where the build applies
   *     it itself
   * @param home the user's home directory, which the command abbreviates to {@code ~}
   * @return the command, with {@code /} between the names on every platform and the path quoted
   *     where the shell would otherwise change it
   */
  static String gradleCommand(String initScript, String home) {
    if (initScript == null) {
      return "./gradlew";
    }
    String path = initScript.replace(File.separatorChar, '/');
    if (home != null) {
      String prefix = home.replace(File.separatorChar, '/') + "/";
      if (path.startsWith(prefix)) {
        // The shell expands ~/ only outside quotes, so only the rest of the path is quoted.
        return "./gradlew --init-script ~/" + shellWord(path.substring(prefix.length()));
      }
    }
    return "./gradlew --init-script " + shellWord(path);
  }

  /** Returns {@code text} as one word that bash and zsh pass on unchanged. */
  static String shellWord(String text) {
    if (!text.isEmpty() && text.chars().allMatch(CribroGuideTask::isShellSafe)) {
      return text;
    }
    return "'" + text.replace("'", "'\\''") + "'";
  }

  private static boolean isShellSafe(int c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || "._/+@%,:=-".indexOf(c) >= 0;
  }

  private static String guide() {
    try (InputStream in = CribroGuideTask.class.getResourceAsStream("guide.md")) {
      if (in == null) {
        throw new IllegalStateException("guide.md is missing from the plugin jar");
      }
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
