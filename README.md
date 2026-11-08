# cribro Gradle plugin

Reports which lines a branch changed that the tests do not execute (JaCoCo) and which changed lines the tests do not
check (PIT), for a Gradle build with Java sources. The build can apply cribro as a settings plugin, or an init script
can apply it without changing the build. The output is plain text sized for an AI agent; there is no HTML report of its
own.

The code started in NullAway's `buildSrc` ([uber/NullAway#1833](https://github.com/uber/NullAway/pull/1833) and
[uber/NullAway#1975](https://github.com/uber/NullAway/pull/1975)), written by the same author.

## Requirements

- Gradle 8.0 or later, running on JDK 11 or later (Gradle 9 itself needs JDK 17). Nothing older than 8.14 has been
  tried.
- Tests that run on JDK 11 or later, since PIT loads this plugin's classes into the test JVM.
- Java sources under the `java` plugin's source sets; Kotlin and Groovy sources are not measured.
- JUnit 4, or JUnit Jupiter or Vintage on the JUnit Platform, for `pitestDiff`.

Tried on pgjdbc (Gradle 9.7.1, JUnit Jupiter), NullAway (Gradle 9.8.1, JUnit 4), and a one-class build on Gradle
8.14.3 whose tests have no `junit-platform-launcher` dependency.

## Install

cribro is applied either by the build itself, as a settings plugin, or by an init script, to a build that knows
nothing about it. Both add the same tasks. Where the build applies the plugin itself, the init script leaves it alone
and the build's version runs. The plugin is not published yet, so for now both start from a build of this repository.
Building it needs JDK 25 where Gradle finds it, for example as `JAVA_HOME`.

### In the build

Publish the plugin to the local Maven repository from a checkout of this repository:

```bash
./gradlew --quiet publishToMavenLocal
```

Then add it to the build's `settings.gradle.kts`; it adds the tasks to every Java project of the build:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.cribrodev.cribro") version "0.1.0-SNAPSHOT"
}
```

In `settings.gradle`, the `pluginManagement` block reads the same, and the plugin line is
`id 'io.github.cribrodev.cribro' version '0.1.0-SNAPSHOT'`. Drop `mavenLocal()` once
the plugin is published.

### Without changing the build

```bash
./gradlew --quiet dist
```

```bash
mkdir -p ~/.gradle/cribro && cp build/dist/* ~/.gradle/cribro/
```

Then pass `--init-script ~/.gradle/cribro/cribro.init.gradle` to every Gradle command that should measure, as shown
under [Use](#use).

The init script loads `cribro.jar` from its own directory. Without a `cribro.jar` beside it, a script built from a
released version resolves that version from the Gradle Plugin Portal, and a script built from a `-SNAPSHOT` version
fails with:

```text
No cribro.jar beside <script>, and cribro <version> is a development build that the Gradle Plugin Portal does not host. Copy build/dist/cribro.jar of the cribro build into <directory>.
```

The init script still loads its jar, or resolves its version from the Portal, in a build that applies cribro itself.

An init script installed before cribro became a settings plugin applies the plugin a second time to a build that
applies it itself, and that build fails. Copy `build/dist/*` and `agent/cribro/SKILL.md` again after updating this
repository.

The plugin jar has no runtime dependencies: PIT is resolved from the target build's repositories when `pitestDiff`
runs.

## Use

`./gradlew -q cribro` prints the guide to the other tasks: the projects that have them, the commands written the way
this build runs them, the properties, and how to act on each report.

```bash
./gradlew -q cribro
```

With the init script:

```bash
./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle -q cribro
```

The two commands an agent runs most are these, written for a build that applies the plugin; with the init script,
add `--init-script ~/.gradle/cribro/cribro.init.gradle` to each:

```bash
./gradlew <project>:test
```

```bash
./gradlew <project>:pitestDiff -Pcribro.pitestTargetTests=<tests>
```

`<project>` is a Gradle project path such as `:app`, which can differ from the directory; the guide lists the paths.
For the root project, the task path is `:test`.

## Tell an agent about it

`./gradlew -q cribro` prints the guide for the version the build runs, so an `AGENTS.md` note or a skill needs only
to name that command.

In a build that applies the plugin, add a note like this to `AGENTS.md`:

```markdown
After changing the Java sources under `src/main` of a module, run `./gradlew <project>:test`, where `<project>` is
that module's Gradle project path, such as `:app`, and act on the diff coverage report it prints. `./gradlew -q cribro`
prints how to read the reports and how to run mutation testing.
```

For a build that does not, install `agent/cribro/SKILL.md` as a skill (`~/.claude/skills/cribro/SKILL.md` for Claude
Code). It tells the agent when to run cribro and how to reach the guide through the init script.

## What the plugin does to a build

The plugin registers `cribro` on the root project, and adds a `cribroProjectElements` configuration to every project;
`cribro` resolves that configuration of each project to list the Java projects. Applied as a settings plugin or through
the init script, cribro works with Isolated Projects, tried on Gradle 9.8.2. On Gradle 8, Isolated Projects fails the
`cribro` task where a subproject has the same group and name as the root project. For every project with the `java`
plugin, it:

- applies `jacoco`, enables it on the `test` task, and has `test` finalized by `jacocoTestReport` with the XML report,
  then by `diffCoverage`. These override the build's own settings after its scripts run, so a build that disables
  JaCoCo still gets the report. Where the build does not apply `jacoco` itself, the other `Test` tasks run without it;
- replaces the JaCoCo version only where it is Gradle's bundled default and older than 0.8.16;
- registers `changedLines` and `pitestDiff`, and a `cribroPitest` configuration with `pitest-command-line`, plus
  `pitest-junit5-plugin` when `test` uses the JUnit Platform, plus `junit-platform-launcher` when the tests do not
  bring one;
- runs PIT in a separate JVM from the `test` task's launcher, with its JVM arguments and system properties.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
