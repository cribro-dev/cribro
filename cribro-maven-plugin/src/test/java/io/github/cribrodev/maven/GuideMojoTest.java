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

import org.junit.jupiter.api.Test;

/** The command the guide tells the reader to run. */
class GuideMojoTest {

  @Test
  void aBuildThatLoadsCribroItselfNeedsNoProperty() {
    assertEquals("./mvnw", GuideMojo.mavenCommand("./mvnw", null, "/home/u"));
  }

  @Test
  void theExtensionJarUnderTheHomeIsWrittenThroughHome() {
    assertEquals(
        "mvn -Dmaven.ext.class.path=\"$HOME\"/cribro/cribro-maven.jar",
        GuideMojo.mavenCommand("mvn", "/home/u/cribro/cribro-maven.jar", "/home/u"));
  }

  @Test
  void aPathWithASpaceIsQuoted() {
    assertEquals(
        "mvn -Dmaven.ext.class.path='/opt/my tools/cribro-maven.jar'",
        GuideMojo.mavenCommand("mvn", "/opt/my tools/cribro-maven.jar", "/home/u"));
  }
}
