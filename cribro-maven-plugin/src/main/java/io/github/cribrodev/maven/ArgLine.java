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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;

/**
 * Surefire's {@code argLine}: the JVM arguments of the forked test JVM, written as one string.
 *
 * <p>Surefire replaces each {@code @{name}} with the project property of that name just before it
 * forks, and leaves a name the project does not define as it is. It then splits the string at
 * whitespace outside quotes, single or double, and drops the quotes.
 */
final class ArgLine {

  private ArgLine() {}

  /** Replaces each {@code @{name}} whose name the properties define, as surefire does. */
  static String expandLate(String argLine, Properties properties) {
    if (argLine == null) {
      return "";
    }
    String expanded = argLine;
    for (String name : properties.stringPropertyNames()) {
      expanded = expanded.replace("@{" + name + "}", properties.getProperty(name, ""));
    }
    return expanded.trim();
  }

  /** Splits the line into arguments at whitespace outside quotes, dropping the quotes. */
  static List<String> split(String argLine) {
    List<String> arguments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inArgument = false;
    char quote = 0;
    for (int i = 0; i < argLine.length(); i++) {
      char c = argLine.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          current.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inArgument = true;
      } else if (Character.isWhitespace(c)) {
        if (inArgument) {
          arguments.add(current.toString());
          current.setLength(0);
          inArgument = false;
        }
      } else {
        current.append(c);
        inArgument = true;
      }
    }
    if (inArgument) {
      arguments.add(current.toString());
    }
    return arguments;
  }

  /** Returns the argument quoted so that {@link #split} gives it back whole. */
  static String quote(String argument) {
    boolean plain =
        !argument.isEmpty()
            && argument.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'');
    if (plain) {
      return argument;
    }
    return argument.indexOf('"') < 0 ? '"' + argument + '"' : '\'' + argument + '\'';
  }

  /** A JaCoCo agent that the arguments attach, with its options. */
  static final class JacocoAgent {
    /** The options after the agent's jar, in their order, such as {@code destfile}. */
    final Map<String, String> options;

    JacocoAgent(Map<String, String> options) {
      this.options = options;
    }

    /**
     * Returns the file the agent writes, resolved against the directory the tests run in; JaCoCo
     * names {@code jacoco.exec} where no {@code destfile} is given.
     */
    File destFile(File workingDirectory) {
      File file = new File(options.getOrDefault("destfile", "jacoco.exec"));
      return file.isAbsolute() ? file : new File(workingDirectory, file.getPath());
    }

    /** Returns whether the agent writes to a file, rather than to a socket or nowhere. */
    boolean writesFile() {
      return options.getOrDefault("output", "file").equals("file");
    }
  }

  /** Returns the JaCoCo agent the arguments attach, or null where they attach none. */
  static JacocoAgent findJacocoAgent(List<String> arguments) {
    for (String argument : arguments) {
      if (isJacocoAgent(argument)) {
        String rest = argument.substring("-javaagent:".length());
        Map<String, String> options = new LinkedHashMap<>();
        int equals = rest.indexOf('=');
        if (equals >= 0) {
          for (String option : rest.substring(equals + 1).split(",")) {
            int separator = option.indexOf('=');
            if (separator > 0) {
              options.put(option.substring(0, separator), option.substring(separator + 1));
            }
          }
        }
        return new JacocoAgent(options);
      }
    }
    return null;
  }

  /** Returns the arguments without any JaCoCo agent. */
  static List<String> withoutJacocoAgents(List<String> arguments) {
    List<String> kept = new ArrayList<>();
    for (String argument : arguments) {
      if (!isJacocoAgent(argument)) {
        kept.add(argument);
      }
    }
    return kept;
  }

  /**
   * Returns whether the argument is a {@code -javaagent:} whose jar file name contains
   * {@code jacoco} in any letter case, such as {@code org.jacoco.agent-<version>-runtime.jar} from
   * a repository or {@code jacocoagent.jar} from a JaCoCo distribution.
   */
  private static boolean isJacocoAgent(String argument) {
    if (!argument.startsWith("-javaagent:")) {
      return false;
    }
    String rest = argument.substring("-javaagent:".length());
    int equals = rest.indexOf('=');
    String jar = equals < 0 ? rest : rest.substring(0, equals);
    String name = new File(jar).getName().toLowerCase(Locale.ROOT);
    return name.contains("jacoco");
  }
}
