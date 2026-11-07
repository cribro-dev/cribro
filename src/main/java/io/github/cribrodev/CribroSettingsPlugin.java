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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;

/**
 * Applies {@link CribroPlugin} to every project of the build, and registers the {@code cribro}
 * task, which prints the guide to the tasks, on the root project.
 *
 * <p>A build applies it in {@code settings.gradle(.kts)} as {@code io.github.cribrodev.cribro}; the
 * init script applies it to a build that does not.
 */
public abstract class CribroSettingsPlugin implements Plugin<Settings> {

  /**
   * The plugin ID. The init script checks the same literal to leave a build that applies cribro
   * alone.
   */
  public static final String ID = "io.github.cribrodev.cribro";

  /**
   * The extra property of {@link Settings} that holds the path of the init script that applied
   * the plugin; absent where the build applies the plugin itself.
   */
  public static final String INIT_SCRIPT_PROPERTY = "cribro.initScript";

  @Override
  public void apply(Settings settings) {
    ExtraPropertiesExtension extra = settings.getExtensions().getExtraProperties();
    String initScript =
        extra.has(INIT_SCRIPT_PROPERTY) ? String.valueOf(extra.get(INIT_SCRIPT_PROPERTY)) : null;
    // Gradle.beforeProject runs for each project as Gradle configures it. allprojects would also
    // configure the projects that configure-on-demand skips, and Isolated Projects forbids it.
    settings
        .getGradle()
        .beforeProject(project -> project.getPluginManager().apply(CribroPlugin.class));
    settings
        .getGradle()
        .rootProject(
            root -> {
              root.getTasks()
                  .register(
                      "cribro",
                      CribroGuideTask.class,
                      task -> {
                        task.setDescription(
                            "Prints how to use diff coverage and diff mutation testing in this"
                                + " build");
                        task.setGroup("help");
                        task.getVersion().set(CribroPlugin.version());
                        // Under Isolated Projects, one project can read another only through a
                        // dependency on it. Depending on every project also makes
                        // configure-on-demand configure all of them, so the list is complete.
                        // Each project gets a graph of its own: in a shared graph, conflict
                        // resolution merges two projects that share a group and a name.
                        task.getProjectPaths()
                            .addAll(
                                root.provider(
                                    () ->
                                        root.getPlugins().hasPlugin(JavaPlugin.class)
                                            ? List.of(":")
                                            : List.<String>of()));
                        List<String> paths = new ArrayList<>();
                        collectPaths(settings.getRootProject(), paths);
                        for (String path : paths) {
                          if (!path.equals(":")) {
                            task.getProjectPaths().addAll(javaPath(root, path));
                          }
                        }
                        task.getGradleCommand()
                            .set(
                                root.getProviders()
                                    .systemProperty("user.home")
                                    .map(home -> CribroGuideTask.gradleCommand(initScript, home))
                                    .orElse(CribroGuideTask.gradleCommand(initScript, null)));
                      });
            });
  }

  /**
   * Returns {@code path} as a one-element list where that project applies the java plugin, and an
   * empty list where it does not.
   *
   * <p>Where the dependency resolves to the root project instead, the plugins of the project that
   * {@code path} names are read directly. Querying the provider throws {@link GradleException}
   * where the dependency fails to resolve, or resolves to another component or to a variant without
   * the {@code appliesJava} attribute, rather than leaving the project out.
   */
  private static Provider<List<String>> javaPath(Project root, String path) {
    Configuration configuration =
        root.getConfigurations()
            .detachedConfiguration(root.getDependencies().project(Map.of("path", path)));
    configuration
        .getAttributes()
        .attribute(
            Usage.USAGE_ATTRIBUTE, root.getObjects().named(Usage.class, CribroPlugin.PROJECT_USAGE))
        .attribute(
            Category.CATEGORY_ATTRIBUTE,
            root.getObjects().named(Category.class, CribroPlugin.PROJECT_USAGE));
    return configuration
        .getIncoming()
        .getResolutionResult()
        .getRootComponent()
        .map(
            component -> {
              for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof UnresolvedDependencyResult unresolved) {
                  throw new GradleException(
                      "Cannot tell whether "
                          + path
                          + " applies the java plugin: "
                          + unresolved.getFailure().getMessage(),
                      unresolved.getFailure());
                }
                ResolvedDependencyResult resolved = (ResolvedDependencyResult) dependency;
                if (resolved.getSelected().getId() instanceof ProjectComponentIdentifier selected
                    && selected.getProjectPath().equals(":")) {
                  // Before Gradle 9, the graph of a detached configuration has the identity of
                  // the root project, so a project with the same group and name resolves to the
                  // root. That project is then read directly.
                  return root.project(path).getPlugins().hasPlugin(JavaPlugin.class)
                      ? List.of(path)
                      : List.<String>of();
                }
                if (!(resolved.getSelected().getId() instanceof ProjectComponentIdentifier project)
                    || !project.getProjectPath().equals(path)) {
                  throw new GradleException(
                      "Cannot tell whether "
                          + path
                          + " applies the java plugin: its dependency resolved to "
                          + resolved.getSelected().getId().getDisplayName());
                }
                // A variant without the Usage and Category attributes matches the request, so
                // where cribroProjectElements cannot be consumed, Gradle may select one.
                Boolean appliesJava =
                    resolved
                        .getResolvedVariant()
                        .getAttributes()
                        .getAttribute(CribroPlugin.APPLIES_JAVA);
                if (appliesJava == null) {
                  throw new GradleException(
                      "Cannot tell whether "
                          + path
                          + " applies the java plugin: its dependency resolved to variant "
                          + resolved.getResolvedVariant().getDisplayName()
                          + " instead of "
                          + CribroPlugin.PROJECT_ELEMENTS_CONFIGURATION);
                }
                return appliesJava ? List.of(path) : List.<String>of();
              }
              throw new GradleException(
                  "Cannot tell whether " + path + " applies the java plugin: no dependency resolved");
            });
  }

  private static void collectPaths(ProjectDescriptor project, List<String> paths) {
    paths.add(project.getPath());
    project.getChildren().forEach(child -> collectPaths(child, paths));
  }
}
