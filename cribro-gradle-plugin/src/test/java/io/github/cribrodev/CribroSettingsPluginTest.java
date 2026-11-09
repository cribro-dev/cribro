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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build gets cribro once, from the settings plugin or from the init script, and the {@code
 * cribro} task tells which of the two applied it.
 *
 * <p>Each test runs Gradle on a build of a Java project {@code :app} and a project {@code :docs}
 * with no plugin. The init script is the one {@code dist} writes, copied with {@code cribro.jar}
 * beside it, or alone where the test is about the missing jar; no test resolves anything from the
 * network.
 */
class CribroSettingsPluginTest {

  @TempDir Path build;

  @TempDir Path home;

  /** Writes the build, with {@code settingsPlugins} as the body of the settings plugins block. */
  private void writeBuild(String settingsPlugins) throws IOException {
    Files.writeString(
        build.resolve("settings.gradle"),
        """
        plugins {
        %s}
        rootProject.name = 'sample'
        include 'app', 'docs'
        """
            .formatted(settingsPlugins),
        UTF_8);
    Files.createDirectories(build.resolve("app"));
    Files.writeString(build.resolve("app/build.gradle"), "plugins { id 'java' }\n", UTF_8);
    Files.createDirectories(build.resolve("docs"));
  }

  /** Copies the init script and the jar from {@code dist}, and returns the init script. */
  private Path installInitScript() throws IOException {
    Files.copy(dist().resolve("cribro.jar"), home.resolve("cribro.jar"));
    return installInitScriptWithoutJar();
  }

  /** Copies the init script alone from {@code dist}, and returns it. */
  private Path installInitScriptWithoutJar() throws IOException {
    return Files.copy(dist().resolve("cribro.init.gradle"), home.resolve("cribro.init.gradle"));
  }

  private static Path dist() {
    return Path.of(System.getProperty("cribro.dist"));
  }

  private BuildResult run(Path initScript, String... tasks) {
    return runner(initScript, tasks).build();
  }

  private GradleRunner runner(Path initScript, String... tasks) {
    List<String> arguments = new ArrayList<>();
    if (initScript != null) {
      arguments.add("--init-script");
      arguments.add(initScript.toString());
    }
    arguments.add("--quiet");
    arguments.addAll(List.of(tasks));
    return GradleRunner.create()
        .withProjectDir(build.toFile())
        .withPluginClasspath()
        .withArguments(arguments);
  }

  /** Returns the line of the guide that runs {@code test}, or the whole output if there is none. */
  private static String testCommand(BuildResult result) {
    return result.getOutput().lines()
        .filter(line -> line.endsWith("<project>:test"))
        .findFirst()
        .orElse(result.getOutput());
  }

  @Test
  void theInitScriptAppliesCribroToABuildThatDoesNotApplyIt() throws IOException {
    writeBuild("");
    Path initScript = installInitScript();

    BuildResult result = run(initScript, "cribro");

    // Whether the directory is written from ~ depends on where the temporary directory lies, and
    // CribroGuideTaskTest covers that, and the quoting a Windows temporary directory needs; here the
    // command has to pass the init script.
    assertEquals(
        "./gradlew --init-script <dir>/cribro.init.gradle <project>:test",
        testCommand(result)
            .replaceFirst("\\S+/cribro\\.init\\.gradle'?", "<dir>/cribro.init.gradle"));
  }

  @Test
  void theDistInitScriptNamesThePluginVersionItWasBuiltWith() throws IOException {
    String initScript =
        Files.readString(dist().resolve("cribro.init.gradle"), UTF_8);

    assertEquals(
        List.of("            classpath \"io.github.cribrodev:cribro-gradle-plugin:"
            + CribroPlugin.version() + "\""),
        initScript.lines().filter(line -> line.contains("cribro-gradle-plugin:")).toList());
  }

  /**
   * The build's copy and the init script's copy come from different class loaders, so a second
   * application would register the {@code cribro} task twice and fail the build.
   */
  @Test
  void theInitScriptLeavesABuildThatAppliesCribroAlone() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Path initScript = installInitScript();

    BuildResult result = run(initScript, "cribro");

    assertEquals("./gradlew <project>:test", testCommand(result));
  }

  @Test
  void theGuideListsOnlyTheJavaProjects() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");

    BuildResult result = run(null, "cribro");

    assertEquals(
        "Projects with diffCoverage, changedLines, and pitestDiff: :app",
        result.getOutput().lines()
            .filter(line -> line.startsWith("Projects with"))
            .findFirst()
            .orElse(result.getOutput()));
  }

  @Test
  void theSettingsPluginAddsTheTasksToTheJavaProject() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");

    assertProjectTasks(run(null, "--dry-run", ":app:changedLines", ":app:diffCoverage", ":app:pitestDiff"));
  }

  @Test
  void theInitScriptAddsTheTasksToTheJavaProject() throws IOException {
    writeBuild("");
    Path initScript = installInitScript();

    assertProjectTasks(
        run(initScript, "--dry-run", ":app:changedLines", ":app:diffCoverage", ":app:pitestDiff"));
  }

  /**
   * Under configure-on-demand, {@code :cribro} configures the root project first, and its
   * dependency on every project then configures the others, so the guide lists all of them. A
   * subproject that depends on the root's evaluation must not make cribro fail.
   */
  @Test
  void aQualifiedCribroListsEveryJavaProjectUnderConfigureOnDemand() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Files.writeString(build.resolve("docs/build.gradle"), "evaluationDependsOn(':')\n", UTF_8);

    BuildResult result = run(null, "--configure-on-demand", ":cribro");

    assertEquals(
        List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
        projectLines(result));
  }

  /** An unqualified task name makes Gradle configure every project to look for the task. */
  @Test
  void anUnqualifiedCribroListsEveryJavaProjectUnderConfigureOnDemand() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");

    BuildResult result = run(null, "--configure-on-demand", "cribro");

    assertEquals(
        List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
        projectLines(result));
  }

  @Test
  void theGuideIsTheSameWhenTheConfigurationCacheIsReused() throws IOException {
    writeBuild("");
    Path initScript = installInitScript();

    // Without --quiet, which hides the line that says the cache was reused.
    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(build.toFile())
            .withPluginClasspath()
            .withArguments("--init-script", initScript.toString(), "--configuration-cache", "cribro");
    BuildResult stored = runner.build();
    BuildResult reused = runner.build();

    assertEquals(
        List.of(
            List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
            List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
            List.of("Reusing configuration cache.")),
        List.of(
            projectLines(stored),
            projectLines(reused),
            reused.getOutput().lines()
                .filter(line -> line.startsWith("Reusing configuration cache"))
                .toList()));
  }

  /**
   * Isolated Projects fails the build when one project reads the state of another, which the guide
   * would otherwise do to find the Java projects.
   */
  @Test
  void theGuideListsTheJavaProjectsUnderIsolatedProjects() throws IOException {
    writeBuild("");
    Path initScript = installInitScript();

    // Without --quiet, which hides the line that says the cache was reused.
    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(build.toFile())
            .withPluginClasspath()
            .withArguments(
                "--init-script",
                initScript.toString(),
                "-Dorg.gradle.unsafe.isolated-projects=true",
                "cribro",
                // Configures the tasks cribro adds to the Java project as well; the build has no
                // repository, so a task that resolves PIT or JaCoCo would fail on its own.
                ":app:changedLines");
    BuildResult stored = runner.build();
    BuildResult reused = runner.build();

    assertEquals(
        List.of(
            List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
            List.of("Projects with diffCoverage, changedLines, and pitestDiff: :app"),
            List.of("Reusing configuration cache.")),
        List.of(
            projectLines(stored),
            projectLines(reused),
            reused.getOutput().lines()
                .filter(line -> line.startsWith("Reusing configuration cache"))
                .toList()));
  }

  /** In one dependency graph, conflict resolution would merge the two into one component. */
  @Test
  void projectsThatShareAGroupAndANameAreEachListed() throws IOException {
    Files.writeString(
        build.resolve("settings.gradle"),
        """
        plugins {
          id 'io.github.cribrodev.cribro'
        }
        rootProject.name = 'sample'
        include 'client:common', 'server:common'
        """,
        UTF_8);
    for (String directory : List.of("client/common", "server/common")) {
      Files.createDirectories(build.resolve(directory));
      Files.writeString(
          build.resolve(directory + "/build.gradle"),
          """
          plugins { id 'java' }
          group = 'org.example'
          version = '1.0'
          """,
          UTF_8);
    }

    BuildResult result = run(null, "-Dorg.gradle.unsafe.isolated-projects=true", "cribro");

    assertEquals(
        List.of("Projects with diffCoverage, changedLines, and pitestDiff: :client:common, :server:common"),
        projectLines(result));
  }

  /** The graph that resolves the subproject has the root project as its root component. */
  @Test
  void aSubprojectThatSharesTheCoordinatesOfTheRootIsListed() throws IOException {
    Files.writeString(
        build.resolve("settings.gradle"),
        """
        plugins {
          id 'io.github.cribrodev.cribro'
        }
        rootProject.name = 'common'
        include 'lib:common'
        """,
        UTF_8);
    Files.createDirectories(build.resolve("lib/common"));
    String javaProject =
        """
        plugins { id 'java' }
        group = 'org.example'
        version = '1.0'
        """;
    Files.writeString(build.resolve("build.gradle"), javaProject, UTF_8);
    Files.writeString(build.resolve("lib/common/build.gradle"), javaProject, UTF_8);

    BuildResult result = run(null, "-Dorg.gradle.unsafe.isolated-projects=true", "cribro");

    assertEquals(
        List.of("Projects with diffCoverage, changedLines, and pitestDiff: :, :lib:common"),
        projectLines(result));
  }

  @Test
  void aRootProjectThatAppliesJavaIsListedUnderIsolatedProjects() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Files.writeString(build.resolve("build.gradle"), "plugins { id 'java' }\n", UTF_8);

    BuildResult result = run(null, "-Dorg.gradle.unsafe.isolated-projects=true", "cribro");

    assertEquals(
        List.of("Projects with diffCoverage, changedLines, and pitestDiff: :, :app"),
        projectLines(result));
  }

  /**
   * A variant without the Usage and Category attributes matches the request, so where the cribro
   * variant cannot be consumed Gradle selects that one, which carries no answer.
   */
  @Test
  void aProjectWhoseCribroVariantCannotBeConsumedFailsTheGuide() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Files.writeString(
        build.resolve("app/build.gradle"),
        """
        plugins { id 'java' }
        configurations.cribroProjectElements.canBeConsumed = false
        configurations.consumable('bare') {
          attributes.attribute(Attribute.of('org.example.flavor', String), 'plain')
        }
        """,
        UTF_8);

    BuildResult result = runner(null, "cribro").buildAndFail();

    assertEquals(
        "   > Cannot tell whether :app applies the java plugin: its dependency resolved to variant"
            + " bare instead of cribroProjectElements",
        result.getOutput().lines()
            .filter(line -> line.contains("Cannot tell whether"))
            .findFirst()
            .orElse(result.getOutput()));
  }

  /** A project with no variant to select fails the guide rather than drop out of the list. */
  @Test
  void aProjectWithNoVariantToConsumeFailsTheGuide() throws IOException {
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Files.writeString(
        build.resolve("docs/build.gradle"),
        "configurations.cribroProjectElements.canBeConsumed = false\n",
        UTF_8);

    BuildResult result = runner(null, "cribro").buildAndFail();

    assertEquals(
        "   > Could not resolve project ':docs'.",
        result.getOutput().lines()
            .filter(line -> line.contains("Could not resolve"))
            .findFirst()
            .orElse(result.getOutput()));
  }

  /** Returns the lines between the version line and the guide, which describe the projects. */
  private static List<String> projectLines(BuildResult result) {
    return result.getOutput().lines()
        .dropWhile(line -> !line.startsWith("cribro "))
        .skip(2)
        .takeWhile(line -> !line.isEmpty())
        .toList();
  }

  @Test
  void aSnapshotInitScriptWithoutItsJarFailsAndNamesTheDirectory() throws IOException {
    assumeTrue(CribroPlugin.version().endsWith("-SNAPSHOT"), "the build's version is a release");
    writeBuild("");
    Path initScript = installInitScriptWithoutJar();

    BuildResult result = runner(initScript, "cribro").buildAndFail();

    assertEquals(
        "No cribro.jar beside " + initScript + ", and cribro " + CribroPlugin.version()
            + " is a development build that the Gradle Plugin Portal does not host. Copy"
            + " cribro-gradle-plugin/build/dist/cribro.jar of the cribro build into " + home + ".",
        result.getOutput().lines()
            .filter(line -> line.contains("No cribro.jar beside"))
            .findFirst()
            .orElse(result.getOutput()));
  }

  /** The jar is needed only to apply cribro, which a build that applies it itself does not need. */
  @Test
  void aSnapshotInitScriptWithoutItsJarLeavesABuildThatAppliesCribroAlone() throws IOException {
    assumeTrue(CribroPlugin.version().endsWith("-SNAPSHOT"), "the build's version is a release");
    writeBuild("  id 'io.github.cribrodev.cribro'\n");
    Path initScript = installInitScriptWithoutJar();

    BuildResult result = run(initScript, "cribro");

    assertEquals("./gradlew <project>:test", testCommand(result));
  }

  private static void assertProjectTasks(BuildResult result) {
    assertEquals(
        List.of(":app:changedLines SKIPPED", ":app:diffCoverage SKIPPED", ":app:pitestDiff SKIPPED"),
        result.getOutput().lines()
            .filter(line -> line.matches(":app:(changedLines|diffCoverage|pitestDiff) .*"))
            .sorted()
            .toList());
  }
}
