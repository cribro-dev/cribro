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

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Holds the {@link Git} every project of a build shares.
 *
 * <p>Every module of a build asks the same questions of the same repository: which ref the diff is
 * taken against, which lines it changed, which files are untracked. A build service is the one
 * object a build has that outlives a task and is shared across projects, so the answers {@link
 * Git} keeps last for the build.
 */
public abstract class GitService implements BuildService<GitService.Parameters> {

  /** The repository every command of this service runs in. */
  public interface Parameters extends BuildServiceParameters {
    DirectoryProperty getRepositoryRoot();
  }

  private Git git;

  /** Returns git in the repository the parameters name, the same instance on every call. */
  public synchronized Git git() {
    if (git == null) {
      git = new Git(getParameters().getRepositoryRoot().get().getAsFile());
    }
    return git;
  }
}
