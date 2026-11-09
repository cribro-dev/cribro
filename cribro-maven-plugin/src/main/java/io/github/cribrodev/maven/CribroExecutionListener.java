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

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the session's execution listener: it marks when each measured test run ends, and prints
 * the diff coverage report when the session ends.
 *
 * <p>The report is printed before the wrapped listener hears that the session ended, so it comes
 * before Maven's summary and its BUILD SUCCESS or BUILD FAILURE line. A {@code MojoExecutionListener}
 * does not do: Maven does not tell it about a mojo that failed with a {@code MojoFailureException},
 * which is how surefire reports a failing test.
 */
final class CribroExecutionListener implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(CribroExecutionListener.class);

  private final ExecutionListener delegate;

  CribroExecutionListener(ExecutionListener delegate) {
    this.delegate = delegate;
  }

  @Override
  public void mojoSucceeded(ExecutionEvent event) {
    ended(event, false);
    delegate.mojoSucceeded(event);
  }

  @Override
  public void mojoFailed(ExecutionEvent event) {
    ended(event, true);
    delegate.mojoFailed(event);
  }

  private static void ended(ExecutionEvent event, boolean failed) {
    if (!CribroMojoListener.isSurefireDefaultTest(event.getMojoExecution())) {
      return;
    }
    TestRun run = CribroState.of(event.getSession()).run(event.getProject());
    if (run != null) {
      run.end = System.currentTimeMillis();
      run.failed = failed;
    }
  }

  @Override
  public void sessionEnded(ExecutionEvent event) {
    MavenSession session = event.getSession();
    try {
      if (Ownership.acts(session)) {
        String text = new CoverageReport(session, CribroState.of(session)).describe();
        if (!text.isEmpty()) {
          // The report goes to stdout so that mvn -q, which hides every info line, still shows
          // it. The Gradle plugin logs it at QUIET for the same reason.
          System.out.println(text);
          System.out.flush();
        }
      }
    } catch (Exception | LinkageError e) {
      // A report that cannot be made must not turn a passing build into a failing one.
      LOG.warn("cribro could not print the diff coverage report", e);
    }
    delegate.sessionEnded(event);
  }

  @Override
  public void projectDiscoveryStarted(ExecutionEvent event) {
    delegate.projectDiscoveryStarted(event);
  }

  @Override
  public void sessionStarted(ExecutionEvent event) {
    delegate.sessionStarted(event);
  }

  @Override
  public void projectSkipped(ExecutionEvent event) {
    delegate.projectSkipped(event);
  }

  @Override
  public void projectStarted(ExecutionEvent event) {
    delegate.projectStarted(event);
  }

  @Override
  public void projectSucceeded(ExecutionEvent event) {
    delegate.projectSucceeded(event);
  }

  @Override
  public void projectFailed(ExecutionEvent event) {
    delegate.projectFailed(event);
  }

  @Override
  public void mojoSkipped(ExecutionEvent event) {
    delegate.mojoSkipped(event);
  }

  @Override
  public void mojoStarted(ExecutionEvent event) {
    delegate.mojoStarted(event);
  }

  @Override
  public void forkStarted(ExecutionEvent event) {
    delegate.forkStarted(event);
  }

  @Override
  public void forkSucceeded(ExecutionEvent event) {
    delegate.forkSucceeded(event);
  }

  @Override
  public void forkFailed(ExecutionEvent event) {
    delegate.forkFailed(event);
  }

  @Override
  public void forkedProjectStarted(ExecutionEvent event) {
    delegate.forkedProjectStarted(event);
  }

  @Override
  public void forkedProjectSucceeded(ExecutionEvent event) {
    delegate.forkedProjectSucceeded(event);
  }

  @Override
  public void forkedProjectFailed(ExecutionEvent event) {
    delegate.forkedProjectFailed(event);
  }
}
