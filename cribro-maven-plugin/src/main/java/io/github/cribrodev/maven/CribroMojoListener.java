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

import io.github.cribrodev.Versions;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.jacoco.agent.AgentJar;

/**
 * Attaches the JaCoCo agent to the test JVM of surefire's {@code default-test} execution, and
 * records the run for the report.
 *
 * <p>The agent goes into the {@code argLine} of the configured mojo, just before it runs, so it
 * reaches the tests whatever the project wrote there: an {@code argLine} that replaces the one
 * JaCoCo's own plugin sets, or none. Where the {@code argLine} already attaches a JaCoCo agent, as
 * a project using {@code jacoco-maven-plugin} does, cribro reads that agent's file rather than
 * attaching a second.
 */
@Named("cribro")
@Singleton
public class CribroMojoListener implements MojoExecutionListener {

  private static final String SUREFIRE = "maven-surefire-plugin";

  private final RepositorySystem repositorySystem;

  @Inject
  public CribroMojoListener(RepositorySystem repositorySystem) {
    this.repositorySystem = repositorySystem;
  }

  /** Returns whether the execution is surefire's test goal in its default-test execution. */
  static boolean isSurefireDefaultTest(MojoExecution execution) {
    return SUREFIRE.equals(execution.getArtifactId())
        && "test".equals(execution.getGoal())
        && "default-test".equals(execution.getExecutionId());
  }

  @Override
  public void beforeMojoExecution(MojoExecutionEvent event) {
    MojoExecution execution = event.getExecution();
    MavenSession session = event.getSession();
    MavenProject project = event.getProject();
    if (!isSurefireDefaultTest(execution)
        || !Ownership.acts(session)
        || !CribroProperties.flag(session, project, "cribro.diffCoverageEnabled", true)) {
      return;
    }
    long start = System.currentTimeMillis();
    TestRun run;
    try {
      run = attach(session, project, event.getMojo(), start);
    } catch (RuntimeException | IOException | ReflectiveOperationException e) {
      run =
          TestRun.unmeasured(
              project,
              start,
              "cribro could not attach JaCoCo to "
                  + SUREFIRE
                  + " "
                  + execution.getVersion()
                  + " in "
                  + project.getArtifactId()
                  + ": "
                  + e);
    }
    CribroState.of(session).started(run);
  }

  private TestRun attach(MavenSession session, MavenProject project, Object mojo, long start)
      throws IOException, ReflectiveOperationException {
    Properties user = session.getUserProperties();
    String testFilter = TestRun.passedByUser(user, "test", (String) callOrNull(mojo, "getTest"));
    String groups = TestRun.passedByUser(user, "groups", (String) callOrNull(mojo, "getGroups"));
    Object forkCount = callOrNull(mojo, "getForkCount");
    if (forkCount != null && "0".equals(forkCount.toString().trim())) {
      return TestRun.unmeasured(
          project,
          start,
          project.getArtifactId()
              + " runs its tests in Maven's own JVM (forkCount=0), where no JaCoCo agent attaches,"
              + " so its tests record no coverage.");
    }
    String argLine = ArgLine.expandLate((String) call(mojo, "getArgLine"), project.getProperties());
    File workingDirectory = (File) callOrNull(mojo, "getWorkingDirectory");
    if (workingDirectory == null) {
      workingDirectory = project.getBasedir();
    }
    ArgLine.JacocoAgent existing = ArgLine.findJacocoAgent(ArgLine.split(argLine));
    if (existing != null) {
      if (!existing.writesFile()) {
        return TestRun.unmeasured(
            project,
            start,
            project.getArtifactId()
                + " attaches a JaCoCo agent with output="
                + existing.options.get("output")
                + ", which writes no file for the report to read.");
      }
      return TestRun.measured(
          project, start, testFilter, groups, existing.destFile(workingDirectory));
    }
    File directory = new File(project.getBuild().getDirectory(), "cribro");
    File execFile = new File(directory, "jacoco.exec");
    if (execFile.getAbsolutePath().indexOf(',') >= 0) {
      // The agent splits its options at commas, and has no way to quote one.
      return TestRun.unmeasured(
          project,
          start,
          "cribro cannot attach JaCoCo in "
              + project.getArtifactId()
              + ": the agent's options cannot carry the comma in "
              + execFile.getAbsolutePath());
    }
    Files.createDirectories(directory.toPath());
    Files.deleteIfExists(execFile.toPath());
    File agent = agentJar(session, project, directory);
    String javaagent =
        "-javaagent:" + agent.getAbsolutePath() + "=destfile=" + execFile.getAbsolutePath()
            // Each fork appends its own session, and the report reads them all.
            + ",append=true";
    String quoted = ArgLine.quote(javaagent);
    call(mojo, "setArgLine", argLine.isEmpty() ? quoted : quoted + " " + argLine);
    return TestRun.measured(project, start, testFilter, groups, execFile);
  }

  /**
   * Returns the agent jar: the one cribro was built with, or the version
   * {@code -Dcribro.jacocoVersion} names, resolved from the project's repositories.
   */
  private File agentJar(MavenSession session, MavenProject project, File directory)
      throws IOException {
    String version = CribroProperties.get(session, project, "cribro.jacocoVersion");
    if (version == null || version.equals(Versions.jacoco())) {
      File jar = new File(directory, "jacocoagent.jar");
      AgentJar.extractTo(jar);
      return jar;
    }
    List<RemoteRepository> repositories = new ArrayList<>(project.getRemoteProjectRepositories());
    repositories.addAll(project.getRemotePluginRepositories());
    ArtifactRequest request =
        new ArtifactRequest(
            new DefaultArtifact("org.jacoco", "org.jacoco.agent", "runtime", "jar", version),
            repositories,
            null);
    try {
      return repositorySystem
          .resolveArtifact(session.getRepositorySession(), request)
          .getArtifact()
          .getFile();
    } catch (ArtifactResolutionException e) {
      throw new IOException("cannot resolve the JaCoCo agent " + version + ": " + e.getMessage(), e);
    }
  }

  @Override
  public void afterMojoExecutionSuccess(MojoExecutionEvent event) {}

  @Override
  public void afterExecutionFailure(MojoExecutionEvent event) {}

  /** Calls a public method surefire's mojo has in every version cribro supports. */
  private static Object call(Object target, String name, Object... arguments)
      throws ReflectiveOperationException {
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
        try {
          return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
          Throwable cause = e.getCause();
          if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
          }
          throw e;
        }
      }
    }
    throw new NoSuchMethodException(target.getClass().getName() + "." + name);
  }

  /** Calls a method an older surefire may lack, and returns null where it does. */
  private static Object callOrNull(Object target, String name) throws ReflectiveOperationException {
    try {
      return call(target, name);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}
