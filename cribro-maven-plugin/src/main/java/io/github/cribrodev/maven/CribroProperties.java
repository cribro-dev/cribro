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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Reads the {@code cribro.*} properties, which a user passes with {@code -D} or a project sets in
 * its {@code <properties>} or {@code .mvn/maven.config}.
 *
 * <p>The command line wins over the project, as it does for Maven's own properties.
 */
final class CribroProperties {

  private CribroProperties() {}

  /** Returns the property, or null where neither the command line nor the project sets it. */
  static String get(MavenSession session, MavenProject project, String name) {
    String value = session.getUserProperties().getProperty(name);
    if (value == null && project != null) {
      value = project.getProperties().getProperty(name);
    }
    return value;
  }

  static int intValue(MavenSession session, MavenProject project, String name, int defaultValue) {
    String value = get(session, project, name);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be an integer, got " + value, e);
    }
  }

  /** A flag passed with no value, as in {@code -Dcribro.pitestShowAll}, reads as true. */
  static boolean flag(MavenSession session, MavenProject project, String name, boolean defaultValue) {
    String value = get(session, project, name);
    if (value == null) {
      return defaultValue;
    }
    return value.isEmpty() || Boolean.parseBoolean(value);
  }
}
