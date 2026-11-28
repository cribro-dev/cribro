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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The arguments of the JVM that runs the tests, split into those PIT's {@code --jvmArgs} can pass
 * and those it cannot.
 */
public final class PitestJvmArgs {

  /** Why an argument with whitespace, a quote, or a {@code #} is left out. */
  static final String ARGUMENT_FILE =
      "the java launcher splits a line of the argument file the test JVM starts with at"
          + " whitespace, removes its quotes, and ends it at a # (hcoles/pitest#1511)";

  /** Why an argument with a {@code {} that no later {@code }} closes is left out. */
  static final String BRACE_LEFT_OPEN =
      "PIT's --jvmArgs joins an argument with a { that no } closes to the argument after it";

  /** Why an argument with a comma and a brace of its own is left out. */
  static final String OWN_BRACE =
      "PIT's --jvmArgs splits at a comma outside braces and removes every brace once a comma is"
          + " inside them";

  private final String value;
  private final Map<String, List<String>> leftOut;

  private PitestJvmArgs(String value, Map<String, List<String>> leftOut) {
    this.value = value;
    this.leftOut = leftOut;
  }

  /**
   * Returns the arguments joined with commas, for {@code --jvmArgs}, with an argument that contains
   * a comma enclosed in braces.
   */
  public String value() {
    return value;
  }

  /** Returns the arguments PIT cannot pass whole, which the test JVM under PIT runs without. */
  public List<String> leftOut() {
    return leftOut.values().stream().flatMap(List::stream).collect(Collectors.toList());
  }

  /**
   * Returns the arguments of {@link #leftOut()} keyed by the reason they are left out, a clause
   * that follows "since", with the reasons in the order of the first argument each one applies to.
   */
  public Map<String, List<String>> leftOutByReason() {
    return leftOut;
  }

  /**
   * Returns the test JVM's arguments, system properties, and heap limit as one comma-separated
   * {@code --jvmArgs} value.
   *
   * <p>For a Gradle {@code Test} task, Gradle moves every {@code -Dname=value} given through
   * {@code jvmArgs} into the task's system properties, so the arguments the test JVM gets are the
   * JVM arguments, the system properties, and the heap limit together.
   *
   * <p>PIT splits {@code --jvmArgs} at each comma outside braces, where a {@code {} opens braces
   * that the next {@code }} closes, then removes every brace from every argument once any comma was
   * inside braces. An argument with a comma is therefore passed in braces, unless it or a passed
   * argument contains a brace of its own, which PIT would remove. An argument that leaves braces
   * open would join the next one, and is left out.
   *
   * <p>PIT writes each argument unquoted on a line of the argument file the test JVM starts with
   * (hcoles/pitest#1511). The {@code java} launcher splits that line at whitespace, removes the
   * quotes {@code "} and {@code '}, and reads the rest of the line after a {@code #} as a comment,
   * so an argument with any of these would reach the test JVM changed, as a stray main class name
   * at worst.
   *
   * <p>An argument PIT cannot pass whole is left out, under the first of these reasons that applies
   * to it in {@link #leftOutByReason()}: the argument file, a brace left open, a brace of its own,
   * a brace in a passed argument. An argument that starts with {@code @} names an argument file,
   * which PIT reads into the test JVM's own without its {@code --add-modules} lines.
   *
   * @param maxHeapSize the heap limit, such as a Gradle test task's {@code maxHeapSize}, or null
   *     where none is set
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
    // A passed argument without a comma keeps its braces only while no comma is in braces.
    List<String> braced =
        arguments.stream().filter(a -> isPlain(a) && hasBrace(a)).collect(Collectors.toList());
    List<String> passed = new ArrayList<>();
    Map<String, List<String>> leftOut = new LinkedHashMap<>();
    for (String argument : arguments) {
      String reason;
      if (isPlain(argument)) {
        passed.add(argument);
        continue;
      } else if (changedByArgumentFile(argument)) {
        reason = ARGUMENT_FILE;
      } else if (leavesBraceOpen(argument)) {
        reason = BRACE_LEFT_OPEN;
      } else if (hasBrace(argument)) {
        reason = OWN_BRACE;
      } else if (!braced.isEmpty()) {
        reason = otherBrace(braced);
      } else {
        passed.add("{" + argument + "}");
        continue;
      }
      leftOut.computeIfAbsent(reason, r -> new ArrayList<>()).add(argument);
    }
    Map<String, List<String>> byReason = new LinkedHashMap<>();
    leftOut.forEach((reason, left) -> byReason.put(reason, List.copyOf(left)));
    return new PitestJvmArgs(String.join(",", passed), Collections.unmodifiableMap(byReason));
  }

  /**
   * Returns why an argument with a comma and no brace is left out, where the passed arguments in
   * {@code braced} have braces that PIT would remove.
   */
  static String otherBrace(List<String> braced) {
    return "PIT's --jvmArgs splits at a comma outside braces, and a comma inside braces would make"
        + " it remove the braces of "
        + String.join(" ", braced);
  }

  /** Returns whether PIT passes the argument as it is, with no comma and no brace left open. */
  private static boolean isPlain(String argument) {
    return !argument.contains(",")
        && !changedByArgumentFile(argument)
        && !leavesBraceOpen(argument);
  }

  /** Returns whether a {@code {} in the argument opens braces that no later {@code }} closes. */
  private static boolean leavesBraceOpen(String argument) {
    return argument.lastIndexOf('{') > argument.lastIndexOf('}');
  }

  /** Returns whether the {@code java} launcher reads the argument back changed from its file. */
  private static boolean changedByArgumentFile(String argument) {
    return argument
        .chars()
        .anyMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '#');
  }

  private static boolean hasBrace(String argument) {
    return argument.indexOf('{') >= 0 || argument.indexOf('}') >= 0;
  }
}
