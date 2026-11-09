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

import io.github.cribrodev.Git;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * What cribro learns during one Maven session, read when the session ends.
 *
 * <p>A build daemon such as mvnd runs many sessions in one JVM and keeps extensions loaded, so the
 * state lives in the repository session's data, which Maven creates for each session, rather than
 * in the singleton components. Its key is this class, so each copy of cribro a build loads keeps
 * a state of its own. Modules build in parallel under {@code -T}, so every collection is safe to
 * write from several threads.
 */
final class CribroState {

  private final Map<String, TestRun> runs = new ConcurrentHashMap<>();

  private final File topDirectory;

  private Git git;

  private boolean gitLookedUp;

  private CribroState(File topDirectory) {
    this.topDirectory = topDirectory;
  }

  static CribroState of(MavenSession session) {
    return (CribroState)
        session
            .getRepositorySession()
            .getData()
            .computeIfAbsent(
                CribroState.class,
                () -> new CribroState(new File(session.getExecutionRootDirectory())));
  }

  void started(TestRun run) {
    runs.put(run.project.getId(), run);
  }

  TestRun run(MavenProject project) {
    return runs.get(project.getId());
  }

  /** Returns the runs in the order of the session's projects. */
  List<TestRun> runs(List<MavenProject> projects) {
    List<TestRun> ordered = new ArrayList<>();
    for (MavenProject project : projects) {
      TestRun run = runs.get(project.getId());
      if (run != null) {
        ordered.add(run);
      }
    }
    return ordered;
  }

  /**
   * Returns git in the repository that holds the build's top directory, or null where none does.
   * Every goal of the session shares the instance, so the session runs each git command, and
   * measures each diff, once.
   */
  synchronized Git git() {
    if (!gitLookedUp) {
      gitLookedUp = true;
      File root = Git.findRepositoryRoot(topDirectory);
      git = root != null ? new Git(root) : null;
    }
    return git;
  }
}
