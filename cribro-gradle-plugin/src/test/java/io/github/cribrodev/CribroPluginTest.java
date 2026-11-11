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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plugin measures the directories of the main source set that hold Java or Kotlin sources,
 * puts the PIT plugins a build names on PIT's classpath, and replaces Gradle's bundled JaCoCo only
 * with a newer one, so the version comparison decides whether a build on a future Gradle is
 * downgraded.
 */
class CribroPluginTest {

  @TempDir Path directory;

  /**
   * Returns a project in the temporary directory that applies the java plugin and cribro, with
   * the gradle.properties given. Resolving a configuration locks files under the Gradle user home
   * for the life of the JVM, so that home lies under the build directory rather than in the
   * temporary directory, which JUnit deletes after the test.
   */
  private Project javaProject(String gradleProperties) throws IOException {
    Files.writeString(directory.resolve("gradle.properties"), gradleProperties);
    Project project =
        ProjectBuilder.builder()
            .withProjectDir(directory.toFile())
            .withGradleUserHomeDir(new File("build/tmp/projectBuilderUserHome"))
            .build();
    project.getPluginManager().apply("java");
    project.getPluginManager().apply(CribroPlugin.class);
    return project;
  }

  private static SourceSet main(Project project) {
    return project
        .getExtensions()
        .getByType(SourceSetContainer.class)
        .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
  }

  @Nested
  class TheSourceRoots {

    /**
     * Returns the roots changedLines reads. The temporary directory lies in no repository, so they
     * are relative to the project directory.
     */
    private List<String> roots(Project project) {
      return project
          .getTasks()
          .named("changedLines", ChangedLinesTask.class)
          .get()
          .getSourceRoots()
          .get()
          .stream()
          .sorted()
          .collect(Collectors.toList());
    }

    @Test
    void onlyTheJavaDirectoryOfAJavaBuildIsARoot() throws IOException {
      Project project = javaProject("");

      assertEquals(List.of("src/main/java"), roots(project));
    }

    @Test
    void aJavaDirectoryThatIsAlsoAResourcesDirectoryStaysARoot() throws IOException {
      Project project = javaProject("");
      main(project).getResources().srcDir("src/main/java");
      main(project).getResources().exclude("**/*.java");

      assertEquals(List.of("src/main/java"), roots(project));
    }

    /**
     * Cribro registers its tasks once the java plugin is applied, and the Kotlin JVM plugin adds
     * {@code src/main/kotlin} to {@code allJava} only after it has applied the java plugin.
     */
    @Test
    void aDirectoryTheKotlinPluginAddsAfterCribroIsARoot() throws IOException {
      Project project = javaProject("");
      main(project).getAllJava().srcDir("src/main/kotlin");

      assertEquals(List.of("src/main/java", "src/main/kotlin"), roots(project));
    }

    @Test
    void pitestDiffSearchesADirectoryTheKotlinPluginAddsAfterCribro() throws IOException {
      Project project = javaProject("");
      main(project).getAllJava().srcDir("src/main/kotlin");

      List<String> searched =
          project
              .getTasks()
              .named("pitestDiff", PitestDiffTask.class)
              .get()
              .getMainSourceDirs()
              .getFiles()
              .stream()
              .map(dir -> project.relativePath(dir).replace(File.separatorChar, '/'))
              .sorted()
              .collect(Collectors.toList());
      assertEquals(List.of("src/main/java", "src/main/kotlin"), searched);
    }
  }

  @Nested
  class ThePitestClasspath {

    /**
     * Returns what the pitest configuration asks for. The default dependencies are added only when
     * the configuration is resolved, and with no repository the result names them as unresolved.
     */
    private List<String> requested(Project project) {
      return project
          .getConfigurations()
          .getByName(CribroPlugin.PITEST_CONFIGURATION)
          .getIncoming()
          .getResolutionResult()
          .getRoot()
          .getDependencies()
          .stream()
          .map(dependency -> dependency.getRequested().getDisplayName())
          .sorted()
          .collect(Collectors.toList());
    }

    @Test
    void withNoPluginsNamedPitRunsAlone() throws IOException {
      Project project = javaProject("cribro.pitestVersion=1.0\n");

      assertEquals(List.of("org.pitest:pitest-command-line:1.0"), requested(project));
    }

    @Test
    void theNamedPluginsJoinPitOnItsClasspath() throws IOException {
      Project project =
          javaProject(
              "cribro.pitestVersion=1.0\n"
                  + "cribro.pitestPlugins=com.arcmutate:pitest-kotlin-plugin:1.5.2, demo:other:2\n");

      assertEquals(
          List.of(
              "com.arcmutate:pitest-kotlin-plugin:1.5.2",
              "demo:other:2",
              "org.pitest:pitest-command-line:1.0"),
          requested(project));
    }
  }

  @Test
  void aLaterPatchIsNewer() {
    assertTrue(CribroPlugin.compareVersions("0.8.13", "0.8.16") < 0);
  }

  /** Compared as text, 0.8.9 would sort after 0.8.16. */
  @Test
  void partsCompareAsNumbers() {
    assertTrue(CribroPlugin.compareVersions("0.8.9", "0.8.16") < 0);
  }

  @Test
  void aSnapshotComparesByItsNumbers() {
    assertEquals(0, CribroPlugin.compareVersions("0.8.16-SNAPSHOT", "0.8.16"));
  }

  @Test
  void aNewerBundledVersionIsKept() {
    assertTrue(CribroPlugin.compareVersions("0.9.1", "0.8.16") > 0);
  }
}
