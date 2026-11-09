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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Prepares a build that loads cribro as an extension.
 *
 * <p>It wraps the session's execution listener, which prints the report, and declares the cribro
 * plugin in every module that does not declare it, so that {@code mvn cribro:pitest-diff} resolves
 * the {@code cribro} prefix with no {@code <pluginGroups>} in the user's settings.
 */
@Named("cribro")
@Singleton
public class CribroLifecycleParticipant extends AbstractMavenLifecycleParticipant {

  static final String GROUP_ID = "io.github.cribrodev";

  static final String ARTIFACT_ID = "cribro-maven-plugin";

  /** The declarations a copy of cribro added, so that the build's own copy can set its version. */
  private static final String INJECTED = "io.github.cribrodev.injected";

  @Override
  public void afterProjectsRead(MavenSession session) {
    Ownership.register(session);
    Set<Plugin> injected = injected(session);
    boolean fromBuild = !Ownership.loadedFromExtClassPath();
    for (MavenProject project : session.getProjects()) {
      Build build = project.getBuild();
      Plugin declared = build.getPluginsAsMap().get(GROUP_ID + ":" + ARTIFACT_ID);
      if (declared == null) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(GROUP_ID);
        plugin.setArtifactId(ARTIFACT_ID);
        plugin.setVersion(Versions.cribro());
        build.addPlugin(plugin);
        build.flushPluginMap();
        injected.add(plugin);
      } else if (fromBuild && injected.contains(declared)) {
        // A copy from maven.ext.class.path declared it first; the build's copy wins, as it does
        // over the Gradle init script.
        declared.setVersion(Versions.cribro());
      }
    }
    ExecutionListener listener = session.getRequest().getExecutionListener();
    session.getRequest().setExecutionListener(new CribroExecutionListener(listener));
  }

  @SuppressWarnings("unchecked")
  private static Set<Plugin> injected(MavenSession session) {
    return (Set<Plugin>)
        session
            .getRepositorySession()
            .getData()
            .computeIfAbsent(
                INJECTED,
                () ->
                    Collections.synchronizedSet(
                        Collections.newSetFromMap(new IdentityHashMap<Plugin, Boolean>())));
  }
}
