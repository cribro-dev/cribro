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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The guide, and the command it tells the reader to run. */
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

  @Test
  void theShippedGuideHasAValueForEveryPlaceholder() throws IOException {
    String template;
    try (InputStream in = GuideMojo.class.getResourceAsStream("guide.md")) {
      template = new String(in.readAllBytes(), UTF_8);
    }
    String guide = GuideMojo.render(template, "1.2.3", "mvn", List.of(":app"), GuideMojo.values());
    assertFalse(guide.contains("{{"), guide);
  }
}
