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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The JVM arguments of a Gradle {@code Test} task, split into those PIT's {@code --jvmArgs} can
 * pass and those it cannot.
 */
public final class PitestJvmArgs {

  private final String value;
  private final List<String> leftOut;

  private PitestJvmArgs(String value, List<String> leftOut) {
    this.value = value;
    this.leftOut = leftOut;
  }

  /** Returns the arguments joined with commas, for {@code --jvmArgs}. */
  public String value() {
    return value;
  }

  /** Returns the arguments PIT cannot pass whole, which the test JVM under PIT runs without. */
  public List<String> leftOut() {
    return leftOut;
  }

  /**
   * Returns the JVM arguments of the test task as one comma-separated {@code --jvmArgs} value.
   *
   * <p>Gradle moves every {@code -Dname=value} given through {@code jvmArgs} into the task's system
   * properties, so the arguments the test JVM gets are the JVM arguments, the system properties,
   * and the heap limit together.
   *
   * <p>PIT splits {@code --jvmArgs} at commas and the minion command line at whitespace, so an
   * argument containing either would reach the test JVM broken, as a stray main class name at
   * worst. Such an argument is left out and listed in {@link #leftOut()}.
   *
   * @param maxHeapSize the test task's {@code maxHeapSize}, or null where it sets none
   */
  public static PitestJvmArgs of(
      List<String> jvmArgs, Map<String, ?> systemProperties, String maxHeapSize) {
    List<String> arguments = new ArrayList<>(jvmArgs);
    for (Map.Entry<String, ?> property : systemProperties.entrySet()) {
      arguments.add("-D" + property.getKey() + "=" + property.getValue());
    }
    if (maxHeapSize != null) {
      arguments.add("-Xmx" + maxHeapSize);
    }
    List<String> passed = new ArrayList<>();
    List<String> leftOut = new ArrayList<>();
    for (String argument : arguments) {
      if (argument.contains(",") || argument.chars().anyMatch(Character::isWhitespace)) {
        leftOut.add(argument);
      } else {
        passed.add(argument);
      }
    }
    return new PitestJvmArgs(String.join(",", passed), List.copyOf(leftOut));
  }
}
