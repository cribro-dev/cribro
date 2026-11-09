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

import java.io.IOException;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Prints which of the lines this branch changed the preceding test run executed, as {@link
 * DiffCoverage} words it.
 *
 * <p>The output goes to the {@code QUIET} log level, which {@code --quiet} still shows, because the
 * point of the task is to reach whoever is reading the test output. What the printed block leaves
 * out is in the file {@link #getOutputFile()} names, which the block ends by naming.
 */
@UntrackedTask(
    because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class DiffCoverageTask extends DefaultTask {

  static final String NO_BASE = DiffCoverage.noBase("-P");

  static final String NO_REPORT = DiffCoverage.NO_REPORT;

  /** The JaCoCo XML reports to read. */
  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getReportFiles();

  /** Source directories of the module, relative to the repository root. */
  @Internal
  public abstract ListProperty<String> getSourceRoots();

  /**
   * The ref whose merge base with HEAD the diff is taken from, as {@link ChangedLines#measure}
   * resolves it.
   */
  @Internal
  public abstract Property<String> getBaseRef();

  /** What the run covered, printed so that a filtered run is not read as missing coverage. */
  @Internal
  public abstract Property<String> getScopeDescription();

  /** How many uncovered lines to print for one file, past which the file states its total. */
  @Internal
  public abstract Property<Integer> getMaxLinesPerFile();

  /** How many uncovered lines to print over the block's lists together. */
  @Internal
  public abstract Property<Integer> getMaxLinesTotal();

  /** Whether the run was narrowed to some of the tests, which the report has to say. */
  @Internal
  public abstract Property<Boolean> getFiltered();

  /** How many files to describe over the block's lists together, most work left first. */
  @Internal
  public abstract Property<Integer> getMaxFiles();

  /** Whether to print the whole report, spending no limit on the console output. */
  @Internal
  public abstract Property<Boolean> getShowAll();

  /**
   * The file the whole report is written to, whatever the console limits leave out.
   *
   * <p>The task prints its path, so that a reader who needs the rest reads a report rather than
   * the JaCoCo XML.
   */
  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  /**
   * The name the build registers {@link GitService} under.
   *
   * <p>The registration and this reference share the constant because a reference that names an
   * unregistered service fails only in a build that runs the task, which no test here does.
   */
  public static final String GIT_SERVICE = "cribro.git";

  /** Runs the git commands, and names the repository the diff and the sources are read from. */
  @ServiceReference(GIT_SERVICE)
  public abstract Property<GitService> getGit();

  @TaskAction
  public void report() throws IOException {
    String text = describeRun();
    if (!text.isEmpty()) {
      getLogger().quiet(text);
    }
  }

  /**
   * Returns the text this run prints, which is empty where the change reached none of the module.
   *
   * <p>Returning the text rather than logging it is what lets a test read the report: the scope
   * line naming the ref that resolved, the message of a run that measured nothing, and the note
   * about a file that could not be read are all outcomes a caller can only otherwise infer.
   *
   * <p>The file {@link #getOutputFile()} names is rewritten here, and is deleted where this run
   * measured nothing, so that it holds this run's report or no report at all. The newest report's
   * modification time stands for when the execution data was recorded.
   */
  String describeRun() throws IOException {
    return DiffCoverage.in(getGit().get().git())
        .baseRef(getBaseRef().getOrNull())
        .sourceRoots(getSourceRoots().get())
        .reports(List.copyOf(getReportFiles().getFiles()))
        .scope(getScopeDescription().get(), getFiltered().get())
        .limits(
            getMaxFiles().get(),
            getMaxLinesPerFile().get(),
            getMaxLinesTotal().get(),
            getShowAll().get())
        .outputFile(getOutputFile().get().getAsFile())
        .describe();
  }
}
