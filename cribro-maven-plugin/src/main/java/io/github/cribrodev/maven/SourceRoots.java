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

import io.github.cribrodev.SourceLines;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.project.MavenProject;

/**
 * The main source directories of a module in the form git diff prints paths in, so that the
 * coverage report and {@code cribro:pitest-diff} select the same changed files.
 */
final class SourceRoots {

  private SourceRoots() {}

  /**
   * Returns the module's main source directories that exist under the repository, relative to it
   * and separated by {@code /}. A root outside the repository, such as a generated one under
   * {@code target}, holds no changed line.
   */
  static List<String> of(File repository, MavenProject project) {
    List<String> roots = new ArrayList<>();
    for (String sourceRoot : project.getCompileSourceRoots()) {
      File directory = new File(sourceRoot);
      Path relative = SourceLines.relativeTo(repository, directory);
      if (relative != null && directory.isDirectory()) {
        roots.add(relative.toString().replace(File.separatorChar, '/'));
      }
    }
    return roots;
  }

  /** Returns whether any of the module's test source directories exists. */
  static boolean hasTestSourceDirectory(MavenProject project) {
    return project.getTestCompileSourceRoots().stream()
        .anyMatch(root -> new File(root).isDirectory());
  }
}
