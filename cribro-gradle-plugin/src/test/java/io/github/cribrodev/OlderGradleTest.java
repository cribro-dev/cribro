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
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On a Gradle whose bundled JaCoCo is older than cribro's, a build that names no JaCoCo version
 * gets cribro's, and a build that names one keeps it.
 *
 * <p>TestKit downloads that Gradle distribution, so the tests need the network.
 */
class OlderGradleTest {

  /**
   * A Gradle that runs on every JDK the CI matrix tests with, and whose bundled JaCoCo, 0.8.14, is
   * older than both {@link CribroPlugin#JACOCO_VERSION} and the 0.8.15 that the Gradle compiling
   * cribro bundles.
   */
  static final String GRADLE_VERSION = "9.6.1";

  @TempDir Path build;

  @Test
  void aBuildThatNamesNoJacocoVersionGetsCribros() throws IOException {
    String output =
        run(
            """
            plugins { id 'java' }
            tasks.register('noop')
            gradle.projectsEvaluated { println "toolVersion=${jacoco.toolVersion}" }
            """);

    assertEquals("toolVersion=" + CribroPlugin.JACOCO_VERSION, output);
  }

  @Test
  void aJacocoVersionTheBuildNamesIsKept() throws IOException {
    String output =
        run(
            """
            plugins { id 'java'; id 'jacoco' }
            jacoco { toolVersion = '0.8.12' }
            tasks.register('noop')
            gradle.projectsEvaluated { println "toolVersion=${jacoco.toolVersion}" }
            """);

    assertEquals(
        "toolVersion=0.8.12",
        output,
        "a version the build names is kept, though it is older than cribro's and the bundled one");
  }

  /** Runs {@code noop} on {@link #GRADLE_VERSION} with cribro applied, and returns the output. */
  private String run(String buildScript) throws IOException {
    Files.writeString(
        build.resolve("settings.gradle"),
        "plugins { id '" + CribroSettingsPlugin.ID + "' }\nrootProject.name = 'demo'\n",
        UTF_8);
    Files.writeString(build.resolve("build.gradle"), buildScript, UTF_8);
    BuildResult result =
        GradleRunner.create()
            .withGradleVersion(GRADLE_VERSION)
            .withProjectDir(build.toFile())
            .withPluginClasspath()
            .withArguments("--quiet", "noop")
            .build();
    return result.getOutput().strip();
  }
}
