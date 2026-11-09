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
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.project.MavenProject;

/**
 * One run of a module's tests under surefire's {@code default-test} execution, as the report needs
 * it: where the JaCoCo agent wrote, and the time window its sessions must start in.
 *
 * <p>A file the agent appends to can hold sessions of earlier builds, of failsafe, or of other
 * surefire executions. Only a session that started while this run's tests ran counts.
 */
final class TestRun {

  final MavenProject project;

  /** When surefire started, in milliseconds since the epoch. */
  final long start;

  /** When surefire finished; zero while it runs, or where the build stopped inside it. */
  volatile long end;

  volatile boolean failed;

  /** The {@code -Dtest} filter the user passed, as {@link #passedByUser} reads it, or null. */
  final String testFilter;

  /** The {@code -Dgroups} filter the user passed, as {@link #passedByUser} reads it, or null. */
  final String groups;

  /** Whether the user narrowed this run with either filter. */
  final boolean filtered;

  /**
   * The directory above every file the agent writes, how many names deep they lie, and their
   * paths below it, separated by {@code /}.
   */
  private final File directory;

  private final int depth;

  private final Pattern names;

  /** The placeholders surefire replaces with the fork's number. */
  private static final Pattern PLACEHOLDER =
      Pattern.compile(
          Pattern.quote("${surefire.forkNumber}") + "|" + Pattern.quote("${surefire.threadNumber}"));

  /** Why the run measured nothing, or null. */
  final String note;

  private TestRun(
      MavenProject project,
      long start,
      String testFilter,
      String groups,
      File directory,
      int depth,
      Pattern names,
      String note) {
    this.project = project;
    this.start = start;
    this.testFilter = testFilter;
    this.groups = groups;
    this.filtered = testFilter != null || groups != null;
    this.directory = directory;
    this.depth = depth;
    this.names = names;
    this.note = note;
  }

  /**
   * A run whose agent writes {@code execFile}. A {@code ${surefire.forkNumber}} or {@code
   * ${surefire.threadNumber}} anywhere in the path stands for one file per fork, as surefire
   * replaces both in each fork's argLine.
   */
  static TestRun measured(
      MavenProject project,
      long start,
      String testFilter,
      String groups,
      File execFile) {
    // The directory above the first name with a placeholder is fixed; the path below it varies.
    Deque<String> names = new ArrayDeque<>();
    File directory = execFile;
    for (File f = execFile; f != null; f = f.getParentFile()) {
      if (PLACEHOLDER.matcher(f.getName()).find()) {
        while (!directory.equals(f)) {
          names.addFirst(directory.getName());
          directory = directory.getParentFile();
        }
        names.addFirst(f.getName());
        directory = f.getParentFile();
      }
    }
    if (names.isEmpty()) {
      names.add(execFile.getName());
      directory = execFile.getParentFile();
    }
    StringBuilder regex = new StringBuilder();
    for (String name : names) {
      if (regex.length() > 0) {
        regex.append('/');
      }
      Matcher placeholder = PLACEHOLDER.matcher(name);
      int from = 0;
      while (placeholder.find()) {
        regex.append(Pattern.quote(name.substring(from, placeholder.start()))).append("\\d+");
        from = placeholder.end();
      }
      regex.append(Pattern.quote(name.substring(from)));
    }
    return new TestRun(
        project,
        start,
        testFilter,
        groups,
        directory,
        names.size(),
        Pattern.compile(regex.toString()),
        null);
  }

  /** A run that records no coverage, for the reason the note gives. */
  static TestRun unmeasured(MavenProject project, long start, String note) {
    return new TestRun(project, start, null, null, null, 0, null, note);
  }

  /** Returns the files the agent wrote, which exist now. */
  List<File> execFiles() {
    List<File> files = new ArrayList<>();
    if (directory == null || !directory.isDirectory()) {
      return files;
    }
    Path root = directory.toPath();
    try {
      Files.walkFileTree(
          root,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          depth,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
              String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
              if (attributes.isRegularFile() && names.matcher(relative).matches()) {
                files.add(file.toFile());
              }
              return FileVisitResult.CONTINUE;
            }

            // A directory that cannot be read, or stops being readable part-way, loses only its
            // own files: the agent of another fork may have written beside it.
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // walkFileTree throws only what a visitor method throws, and none of these throws.
    }
    return files;
  }

  /** Returns whether a session that started at the given time belongs to this run. */
  boolean contains(long sessionStart) {
    long until = end == 0 ? Long.MAX_VALUE : end;
    return sessionStart >= start && sessionStart <= until;
  }

  /**
   * Returns the value of the surefire filter {@code name}, {@code test} or {@code groups}, where the
   * user passed it on the command line or in {@code .mvn/maven.config}, or else null. A filter the
   * POM sets runs on every build, so a run with it is the module's whole suite, and running the
   * whole suite cannot clear a warning about a filtered run.
   *
   * @param value the filter surefire runs with, from either source, or null
   */
  static String passedByUser(Properties userProperties, String name, String value) {
    return value != null && userProperties.containsKey(name) ? value : null;
  }
}
