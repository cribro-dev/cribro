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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SurefireConfigurationTest {

  /**
   * Surefire's configuration is filtered to these names before the configurator fills the fields,
   * so a parameter missing from them reaches PIT as its default, and a static field taken for one
   * would hand the configurator a parameter it cannot set.
   */
  @Test
  void theParametersReadAreTheConfigurationFields() {
    assertEquals(
        Set.of(
            "argLine",
            "systemProperties",
            "systemPropertyVariables",
            "promoteUserPropertiesToSystemProperties",
            "environmentVariables",
            "jvm",
            "jdkToolchain",
            "workingDirectory",
            "additionalClasspathElements",
            "classpathDependencyExcludes",
            "classpathDependencyScopeExclude",
            "additionalClasspathDependencies",
            "excludedEnvironmentVariables",
            "groups",
            "excludedGroups",
            "includes",
            "includesFile",
            "excludes",
            "excludesFile",
            "systemPropertiesFile"),
        SurefireConfiguration.NAMES);
  }
}
