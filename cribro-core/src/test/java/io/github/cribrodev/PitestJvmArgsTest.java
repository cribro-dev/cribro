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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The test JVM under PIT gets the JVM arguments, the system properties, and the heap limit of the
 * Gradle test task.
 *
 * <p>The system properties used to be left out: Gradle moves a {@code -D} given through {@code
 * jvmArgs} into the system properties, and the jmh tests that read their source directories from
 * such properties failed under PIT and were skipped.
 */
class PitestJvmArgsTest {

  @Test
  void systemPropertiesReachTheTestJvm() {
    assertEquals(
        "--add-opens=a/b=ALL-UNNAMED,-Dsources=/tmp/src,-Xmx1g",
        PitestJvmArgs.of(List.of("--add-opens=a/b=ALL-UNNAMED"), Map.of("sources", "/tmp/src"), "1g")
            .value());
  }

  @Test
  void aTaskWithNoHeapLimitPassesNone() {
    assertEquals("-Da=1", PitestJvmArgs.of(List.of(), Map.of("a", 1), null).value());
  }

  /** PIT would split the value into two arguments and pass the test JVM both halves. */
  @Test
  void anArgumentWithACommaIsLeftOut() {
    PitestJvmArgs args = PitestJvmArgs.of(List.of("-ea"), Map.of("packages", "a,b"), null);
    assertEquals("-ea", args.value());
    assertEquals(List.of("-Dpackages=a,b"), args.leftOut());
  }

  /**
   * pgjdbc sets {@code junit.jupiter.execution.timeout.default=5 m}; passed on, the minion JVM read
   * {@code m} as its main class and PIT failed before any mutant ran.
   */
  @Test
  void anArgumentWithWhitespaceIsLeftOut() {
    PitestJvmArgs args =
        PitestJvmArgs.of(List.of(), Map.of("junit.jupiter.execution.timeout.default", "5 m"), null);
    assertEquals("", args.value());
    assertEquals(List.of("-Djunit.jupiter.execution.timeout.default=5 m"), args.leftOut());
  }
}
