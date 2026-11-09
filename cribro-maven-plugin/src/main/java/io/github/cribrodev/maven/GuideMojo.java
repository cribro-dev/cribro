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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cribrodev.Versions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Prints how to use cribro in this build: the commands to run, how to read their output, and the
 * properties that change it.
 */
@Mojo(name = "guide", aggregator = true, threadSafe = true)
public class GuideMojo extends AbstractMojo {

  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession session;

  @Override
  public void execute() {
    List<String> modules = new ArrayList<>();
    for (MavenProject project : session.getProjects()) {
      if (SourceRoots.hasTestSourceDirectory(project)) {
        modules.add(":" + project.getArtifactId());
      }
    }
    File top = new File(session.getExecutionRootDirectory());
    String extClassPath = session.getSystemProperties().getProperty("maven.ext.class.path");
    System.out.println(
        render(
            guide(),
            Versions.cribro(),
            mavenCommand(
                new File(top, "mvnw").isFile() ? "./mvnw" : "mvn",
                extClassPath,
                System.getProperty("user.home")),
            modules,
            Map.of(
                "pitestVersion",
                Versions.pitest(),
                "pitestJunit5PluginVersion",
                Versions.pitestJunit5Plugin(),
                "jacocoVersion",
                Versions.jacoco())));
    System.out.flush();
  }

  /**
   * Returns the guide for this build.
   *
   * @param template the guide, with {@code {{mvn}}} where the Maven command goes and {@code
   *     {{name}}} for each entry of {@code values}
   * @param modules the modules with tests, as {@code -pl} selects them
   */
  static String render(
      String template,
      String version,
      String mavenCommand,
      List<String> modules,
      Map<String, String> values) {
    StringBuilder out = new StringBuilder();
    out.append("cribro ").append(version).append("\n\n");
    if (modules.isEmpty()) {
      out.append("No module of this build has tests, so cribro has nothing to run.\n");
    } else {
      out.append("Modules with tests: ").append(String.join(", ", modules)).append("\n");
    }
    out.append('\n');
    String guide = template.replace("{{mvn}}", mavenCommand);
    for (Map.Entry<String, String> value : values.entrySet()) {
      guide = guide.replace("{{" + value.getKey() + "}}", value.getValue());
    }
    return out.append(guide).toString();
  }

  /**
   * Returns the command that runs Maven with cribro loaded.
   *
   * @param mvn {@code ./mvnw} where the build has the wrapper, else {@code mvn}
   * @param extClassPath the jar given with {@code -Dmaven.ext.class.path}, or null where the build
   *     loads cribro itself
   * @param home the user's home directory, which the command writes as {@code "$HOME"}
   */
  static String mavenCommand(String mvn, String extClassPath, String home) {
    if (extClassPath == null || extClassPath.isEmpty()) {
      return mvn;
    }
    String path = extClassPath.replace(File.separatorChar, '/');
    if (home != null) {
      String prefix = home.replace(File.separatorChar, '/') + "/";
      if (path.startsWith(prefix)) {
        // Neither bash nor zsh expands ~ after the = of an argument, so the home is a variable.
        return mvn + " -Dmaven.ext.class.path=\"$HOME\"/" + shellWord(path.substring(prefix.length()));
      }
    }
    return mvn + " -Dmaven.ext.class.path=" + shellWord(path);
  }

  /** Returns {@code text} as one word that bash and zsh pass on unchanged. */
  static String shellWord(String text) {
    if (!text.isEmpty() && text.chars().allMatch(GuideMojo::isShellSafe)) {
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
    try (InputStream in = GuideMojo.class.getResourceAsStream("guide.md")) {
      if (in == null) {
        throw new IllegalStateException("guide.md is missing from the cribro jar");
      }
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
