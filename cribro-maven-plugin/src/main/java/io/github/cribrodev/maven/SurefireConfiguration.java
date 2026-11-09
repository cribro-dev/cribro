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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;

/**
 * The parameters of surefire's {@code default-test} execution that decide how its tests run, read
 * so that PIT runs them the same way.
 *
 * <p>Maven's own configurator fills the fields from the execution's configuration, so that
 * expressions, defaults, and the conversion to maps and lists come out as surefire itself gets
 * them.
 */
public class SurefireConfiguration {

  /**
   * The parameters read, by the name surefire gives them: the public instance fields of this
   * class, which the configurator fills by name.
   */
  static final Set<String> NAMES =
      Arrays.stream(SurefireConfiguration.class.getFields())
          .filter(field -> !Modifier.isStatic(field.getModifiers()))
          .map(Field::getName)
          .collect(Collectors.toUnmodifiableSet());

  public String argLine;

  public Properties systemProperties = new Properties();

  public Map<String, String> systemPropertyVariables = new LinkedHashMap<>();

  /** Surefire 3.0.0-M4 and later can turn this off; before, user properties always pass. */
  public boolean promoteUserPropertiesToSystemProperties = true;

  public Map<String, String> environmentVariables = new LinkedHashMap<>();

  public String jvm;

  public Map<String, String> jdkToolchain = new LinkedHashMap<>();

  public File workingDirectory;

  public List<String> additionalClasspathElements = new ArrayList<>();

  public List<String> classpathDependencyExcludes = new ArrayList<>();

  public String classpathDependencyScopeExclude;

  /** Surefire 3.2 and later. */
  public List<Dependency> additionalClasspathDependencies = new ArrayList<>();

  public List<String> excludedEnvironmentVariables = new ArrayList<>();

  public String groups;

  public String excludedGroups;

  /**
   * The includes surefire runs where neither {@code includes} nor {@code includesFile} lists one;
   * Maven passes an empty list when the POM sets none.
   */
  static final List<String> DEFAULT_INCLUDES =
      List.of("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java");

  public List<String> includes = new ArrayList<>();

  public File includesFile;

  /**
   * The exclude surefire applies where neither {@code excludes} nor {@code excludesFile} lists one:
   * every nested class.
   */
  static final List<String> DEFAULT_EXCLUDES = List.of("**/*$*");

  /**
   * Returns the value with surefire's {@code ${surefire.forkNumber}} and {@code
   * ${surefire.threadNumber}} replaced by 1, the number of surefire's first fork. PIT starts its
   * minions with one set of arguments, so the minions running at once share the number.
   */
  static String firstFork(String value) {
    return value.replace("${surefire.forkNumber}", "1").replace("${surefire.threadNumber}", "1");
  }

  public List<String> excludes = new ArrayList<>();

  public File excludesFile;

  public File systemPropertiesFile;
}
