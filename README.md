# cribro

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cribro-dev/cribro-maven-plugin)](https://central.sonatype.com/artifact/io.github.cribro-dev/cribro-maven-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.cribro-dev.cribro)](https://plugins.gradle.org/plugin/io.github.cribro-dev.cribro)

cribro reports which lines a branch changed that no test executes, and which changed lines no test would notice
breaking. It works in Gradle and Maven builds with Java or Kotlin sources: `test` ends with a diff coverage report from
JaCoCo, and a separate task runs PIT mutation testing on the changed lines only. The reports are plain text sized for
an AI agent, so an agent that changed the code reads them in the build output and writes the tests that are missing.
There is no HTML report of its own.

A coverage figure for the whole project barely moves when a change adds untested code. cribro looks only at the lines
the branch changed since its merge base with the upstream branch, or with `origin/HEAD`, `origin/main`, or
`origin/master` where there is no upstream. `-Pcribro.diffBase=<ref>` in Gradle, or `-Dcribro.diffBase=<ref>` in Maven,
picks another base.

On [pgjdbc/pgjdbc#4036](https://github.com/pgjdbc/pgjdbc/pull/4036), `test` ends with a block like this one, cut here
to one file:

```text
Diff coverage against origin/HEAD (a340cb2b0), from :postgresql:test --tests *SRVLookupTest
  a filtered run leaves the rest of the change looking unexecuted
  ordered by uncovered changed lines, then uncovered branches

  pgjdbc/src/main/java/org/postgresql/Driver.java
    12/16 changed lines executed, 12/22 branches covered
    uncovered changed code:
      254: not executed; 0 of 4 branches covered: if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:postgresql+srv:")) {
      731: not executed: String srvHost = PGProperty.SRV_HOST.getOrDefault(props);
```

The code started in NullAway's `buildSrc` ([uber/NullAway#1833](https://github.com/uber/NullAway/pull/1833) and
[uber/NullAway#1975](https://github.com/uber/NullAway/pull/1975)), written by the same author.

## Install

The Gradle plugin is `io.github.cribro-dev.cribro` on the Gradle Plugin Portal. The Maven extension is
`io.github.cribro-dev:cribro-maven-plugin` on Maven Central. Both have the same version numbers. `<version>` below is
the latest release, which the badges at the top of this page show.

Each build tool can take cribro in one of two ways. Added to the build, cribro is part of the project, and everyone
who builds the project gets it. Installed on your machine, cribro measures any project you build, and no project file
changes.

| Build tool | You want | Section | Files it changes |
| --- | --- | --- | --- |
| Gradle | cribro for everyone who builds the project | [Add cribro to a Gradle build](#add-cribro-to-a-gradle-build) | The project's `settings.gradle(.kts)` and `AGENTS.md` |
| Gradle | cribro for yourself, in projects you do not want to modify | [Install cribro on your machine for any Gradle build](#install-cribro-on-your-machine-for-any-gradle-build) | `~/.gradle/cribro/` and `~/.claude/skills/cribro/` |
| Maven | cribro for everyone who builds the project | [Add cribro to a Maven build](#add-cribro-to-a-maven-build) | The project's `.mvn/extensions.xml` and `AGENTS.md` |
| Maven | cribro for yourself, in projects you do not want to modify | [Install cribro on your machine for any Maven build](#install-cribro-on-your-machine-for-any-maven-build) | `~/.m2/cribro/` and `~/.claude/skills/cribro/` |

## Gradle

For a Gradle build, cribro is a settings plugin, `io.github.cribro-dev.cribro`. It attaches JaCoCo to the `test`
task of every Java project, which then ends with the diff coverage report, and adds a `pitestDiff` task that runs PIT
on the changed lines.

### Gradle requirements

A Gradle build needs:

- Gradle 8.0 or later, running on JDK 11 or later (Gradle 9 itself needs JDK 17). Nothing older than 8.14 has been
  tried.
- Tests that run on JDK 11 or later, since PIT loads this plugin's classes into the test JVM.
- Java or Kotlin sources under the main source set of the `java` plugin, which the Kotlin JVM plugin applies;
  `src/main/kotlin` counts. Groovy sources, Kotlin Multiplatform, and Android builds are not measured.
- JUnit 4, or JUnit Jupiter or Vintage on the JUnit Platform, for `pitestDiff`.

Tried on pgjdbc (Gradle 9.4.1 and 9.7.1, JUnit Jupiter), NullAway (Gradle 9.8.1, JUnit 4), and a one-class build on
Gradle 8.14.3 whose tests have no `junit-platform-launcher` dependency. CI runs `test pitestDiff` through the init
script on a one-class build with both Gradle 8.14.3 and its tests on JDK 11.

### Add cribro to a Gradle build

1. Add the plugin to the project's `settings.gradle.kts`. It adds the tasks to every project of the build that applies
   the `java` plugin, which the Kotlin JVM plugin applies too:

   ```kotlin
   plugins {
       id("io.github.cribro-dev.cribro") version "<version>"
   }
   ```

   Where the file already has a `plugins` block, add the line to it instead of writing a second one. In
   `settings.gradle`, the line is `id 'io.github.cribro-dev.cribro' version '<version>'`. Gradle resolves the plugin
   from the Plugin Portal; where the build's `pluginManagement` block lists its own `repositories`, keep
   `gradlePluginPortal()` among them.

2. Check that the build applies it, from the project's root directory. The command prints `cribro <version>` and the
   projects that have the tasks:

   ```bash
   ./gradlew -q cribro
   ```

3. Tell the agents that work on the project. `./gradlew -q cribro` prints the guide for the version the build runs, so
   a note in `AGENTS.md`, or in whichever file the project's agents read, needs only to name that command:

   ```markdown
   After changing the Java or Kotlin sources under `src/main` of a module, run `./gradlew <project>:test`, where
   `<project>` is that module's Gradle project path, such as `:app`, and act on the diff coverage report it
   prints. `./gradlew -q cribro` prints how to read the reports and how to run mutation testing.
   ```

To upgrade, change the version in `settings.gradle(.kts)`.
[What cribro changes in a Gradle build](#what-cribro-changes-in-a-gradle-build) lists the settings it overrides in each
project.

<details>
<summary>Build cribro from source instead</summary>

Building cribro needs a JDK 25 where Gradle finds it, for example as `JAVA_HOME`; the build does not download one. A
build from source has the version `cribro.version` from `gradle.properties` with `-SNAPSHOT` appended, and it resolves
only from the local Maven repository of the machine that built it. A clone of the project on any other
machine, CI included, fails to resolve the plugin, so keep this change out of a repository that others build.

1. Clone this repository into a directory outside the project, `<cribro-dir>` below, and publish the plugin to the
   local Maven repository:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet publishToMavenLocal
   ```

2. In the project's `settings.gradle.kts`, add `mavenLocal()` to the plugin repositories, and use the `-SNAPSHOT`
   version in step 1 of the release install:

   ```kotlin
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
       }
   }
   ```

   A `repositories` block replaces Gradle's default of the Plugin Portal, so keep `gradlePluginPortal()` in it.

</details>

### Install cribro on your machine for any Gradle build

The init script applies cribro to a build that knows nothing about it, such as a clone of pgjdbc. Gradle runs it only
in a command that names it with `--init-script`. The script resolves the plugin from the Gradle Plugin Portal.

1. Download the init script of the latest release into `~/.gradle/cribro/`, with its version filled in, and the agent
   skill into `~/.claude/skills/cribro/`. The skill tells the agent when to run cribro and how to reach the guide
   through the init script, and it covers Maven builds too. `agent/cribro/SKILL.md` is a plain skill file; the
   commands install it for Claude Code:

   ```bash
   version=$(curl -fsSL https://api.github.com/repos/cribro-dev/cribro/releases/latest | sed -n 's/.*"tag_name": *"v\([^"]*\)".*/\1/p')
   mkdir -p ~/.gradle/cribro ~/.claude/skills/cribro
   curl -fsSL "https://raw.githubusercontent.com/cribro-dev/cribro/v$version/cribro-gradle-plugin/src/dist/cribro.init.gradle" | sed "s/@version@/$version/g" > ~/.gradle/cribro/cribro.init.gradle
   curl -fsSL -o ~/.claude/skills/cribro/SKILL.md "https://raw.githubusercontent.com/cribro-dev/cribro/v$version/agent/cribro/SKILL.md"
   ```

2. Check it from the root directory of the project you want to measure. The command prints `cribro` with the version
   and the projects that have the tasks:

   ```bash
   ./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle -q cribro
   ```

To upgrade, run step 1 again.

<details>
<summary>Build cribro from source instead</summary>

Building cribro needs a JDK 25 where Gradle finds it, for example as `JAVA_HOME`; the build does not download one.

1. Clone this repository into a directory outside any project, `<cribro-dir>` below, and build the init script and
   the plugin jar into `cribro-gradle-plugin/build/dist`:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet dist
   ```

2. Copy them into `~/.gradle/cribro/`, and the skill into `~/.claude/skills/cribro/`. Run this from `<cribro-dir>`:

   ```bash
   mkdir -p ~/.gradle/cribro && cp cribro-gradle-plugin/build/dist/* ~/.gradle/cribro/
   ```

   ```bash
   mkdir -p ~/.claude/skills/cribro && cp agent/cribro/SKILL.md ~/.claude/skills/cribro/
   ```

The init script loads `cribro.jar` from its own directory where there is one. A script built from a `-SNAPSHOT`
version without its jar fails with:

```text
No cribro.jar beside <script>, and cribro <version> is a development build that the Gradle Plugin Portal does not host. Copy cribro-gradle-plugin/build/dist/cribro.jar of the cribro build into <directory>.
```

After `git pull` in `<cribro-dir>`, run `./gradlew --quiet dist` and step 2 again. An init script built before cribro
became a settings plugin applies the plugin a second time to a build that applies it itself, and that build fails.

</details>

For Gradle, both ways add the same tasks. Where the build applies the plugin itself, the init script leaves it alone
and the build's version runs, so the two ways can coexist.

The plugin jar has no runtime dependencies: PIT is resolved from the target build's repositories when `pitestDiff`
runs.

### Check a change in a Gradle build

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

`<project>` is a Gradle project path such as `:app`, which can differ from the directory: pgjdbc's `pgjdbc/` directory
is `:postgresql`. The guide lists the paths. For the root project, the task path is `:test`.

### What cribro changes in a Gradle build

The plugin registers `cribro` on the root project, and adds a `cribroProjectElements` configuration to every project;
`cribro` resolves that configuration of each project to list the projects that have the tasks. Applied as a settings
plugin or through the init script, cribro works with Isolated Projects, tried on Gradle 9.8.2. On Gradle 8, Isolated
Projects fails the `cribro` task where a subproject has the same group and name as the root project. For every project
with the `java` plugin, it:

- applies `jacoco`, enables it on the `test` task, and has `test` finalized by `jacocoTestReport` with the XML report,
  then by `diffCoverage`. These override the build's own settings after its scripts run, so a build that disables
  JaCoCo still gets the report. Where the build does not apply `jacoco` itself, the other `Test` tasks run without it;
- replaces the JaCoCo version only where it is Gradle's bundled default and older than 0.8.16;
- registers `changedLines` and `pitestDiff`, and a `cribroPitest` configuration with `pitest-command-line`, plus
  `pitest-junit5-plugin` when `test` uses the JUnit Platform, plus `junit-platform-launcher` when the tests do not
  bring one;
- runs PIT in a separate JVM from the `test` task's launcher, with its JVM arguments and system properties.

## Maven

For a Maven build, cribro is a Maven extension and plugin, `io.github.cribro-dev:cribro-maven-plugin`. Loaded as an
extension, it attaches JaCoCo to the tests of surefire's `default-test` execution and prints the diff coverage report
before Maven's build summary. Its goal `cribro:pitest-diff` runs PIT on the changed lines.

### Maven requirements

- Maven 3.9 running on JDK 11 or later; tried on 3.9.16. Maven 4 has not been tried.
- CI tests the extension on JDK 17 and later. It is compiled for JDK 11, but no test runs Maven on JDK 11.
- Tests run by surefire's `default-test` execution, in a forked JVM.
- Java sources under the compile source roots, or Kotlin sources under those roots or the `sourceDirs` of
  `kotlin-maven-plugin`.
- For `cribro:pitest-diff`: tests on JUnit 4, or on JUnit Jupiter or Vintage on the JUnit Platform, in a JVM of JDK 11
  or later.

Tried on Guava, whose tests live in `guava-tests` and test the code of `guava`, without changing its build.

### Add cribro to a Maven build

1. Add `.mvn/extensions.xml` to the project's root directory. Where the file exists, add the `<extension>` element to
   it, with the latest release in place of `VERSION`. Maven resolves the extension from Maven Central.

   ```xml
   <extensions>
     <extension>
       <groupId>io.github.cribro-dev</groupId>
       <artifactId>cribro-maven-plugin</artifactId>
       <version>VERSION</version>
     </extension>
   </extensions>
   ```

2. Check it from the project's root directory. The command prints `cribro <version>` and the modules with tests:

   ```bash
   mvn -q cribro:guide
   ```

3. Tell the agents that work on the project. `mvn -q cribro:guide` prints the guide for the version the build loads,
   so a note in `AGENTS.md`, or in whichever file the project's agents read, needs only to name it:

   ```markdown
   After changing the Java or Kotlin sources under `src/main` of a module, run `mvn -pl <module> -am test`, where
   `<module>` is the module whose tests exercise the change, such as `:app`, and act on the diff coverage report it
   prints.
   `mvn -q cribro:guide` prints how to read the reports and how to run mutation testing.
   ```

To upgrade, change the version in `.mvn/extensions.xml`.

### Install cribro on your machine for any Maven build

Maven loads cribro from a jar passed with `-Dmaven.ext.class.path`. The release does not publish that jar, so this way
builds it from source, and needs a JDK 25 where Gradle finds it, for example as `JAVA_HOME`. The build has the version
`cribro.version` from `gradle.properties` with `-SNAPSHOT` appended.

1. Clone this repository into a directory outside the project, `<cribro-dir>` below, publish the plugin to the local
   Maven repository, and build the extension jar into `cribro-maven-plugin/build/dist`:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet publishToMavenLocal :cribro-maven-plugin:extensionJar
   ```

2. Copy the extension jar into `~/.m2/cribro/`. Run this from `<cribro-dir>`:

   ```bash
   mkdir -p ~/.m2/cribro && cp cribro-maven-plugin/build/dist/cribro-maven.jar ~/.m2/cribro/
   ```

   The jar alone measures diff coverage. `cribro:pitest-diff` and `cribro:guide` also need the plugin in a repository
   Maven reads, which step 1 put in the local one.

3. Check it from the project's root directory. The command prints `cribro` with the version you built and the modules
   with tests:

   ```bash
   mvn -Dmaven.ext.class.path="$HOME"/.m2/cribro/cribro-maven.jar -q cribro:guide
   ```

4. Install the agent skill, which tells the agent when to run cribro and how to reach the guide through the extension
   jar. `agent/cribro/SKILL.md` is a plain skill file; for Claude Code, run this from `<cribro-dir>`:

   ```bash
   mkdir -p ~/.claude/skills/cribro && cp agent/cribro/SKILL.md ~/.claude/skills/cribro/
   ```

After `git pull` in `<cribro-dir>`, run steps 1 and 2 again, so that the extension jar and the plugin in the local
Maven repository are the same version.

Where a build loads cribro both ways, the build's copy acts and the other stands down.

### Check a change in a Maven build

`cribro:guide` prints the commands written for this build, the properties, and how to act on each report. The two
commands an agent runs most are these; with cribro installed on your machine, add
`-Dmaven.ext.class.path="$HOME"/.m2/cribro/cribro-maven.jar` to each:

```bash
mvn -pl <module> -am test
```

```bash
mvn -pl <module> -am test-compile cribro:pitest-diff -Dcribro.pitestTargetTests=<tests>
```

`<module>` is the module whose tests exercise the change, written `:artifactId`. The tests of every module in the
build count, so `-am` adds the modules the tests depend on, and their changed code is measured too. Properties are
the Gradle ones with `-D` in place of `-P`.

### What cribro changes in a Maven build

Loaded as an extension, cribro:

- adds a JaCoCo agent to the `argLine` of surefire's `default-test` execution just before it runs, whatever the POM
  sets there. Where the `argLine` already attaches a JaCoCo agent, as `jacoco-maven-plugin`'s `prepare-agent` does,
  cribro reads that agent's file instead;
- writes `target/cribro/jacoco.xml` and `target/cribro/diff-coverage.txt` in each module the change reached;
- declares `cribro-maven-plugin` in every module that does not declare it, so that the `cribro:` prefix resolves.

`cribro:pitest-diff` runs PIT in a separate JVM, with the classpath, JVM, `argLine`, system properties, environment
variables, working directory, groups, includes, and excludes of surefire's `default-test` execution.

## Tell AI agents to use cribro

An agent learns about cribro from one of two places:

- the `AGENTS.md` note of a build that applies cribro, step 3 of
  [Add cribro to a Gradle build](#add-cribro-to-a-gradle-build) or of
  [Add cribro to a Maven build](#add-cribro-to-a-maven-build);
- the agent skill installed with cribro on your machine, in
  [Install cribro on your machine for any Gradle build](#install-cribro-on-your-machine-for-any-gradle-build) or
  [Install cribro on your machine for any Maven build](#install-cribro-on-your-machine-for-any-maven-build). One skill
  covers both build tools.

## Kotlin sources

A changed file is matched to its JaCoCo counters and its PIT mutants by the package it declares and its name, which is
what its class files record, so a Kotlin file whose directory does not follow its package is still measured. The block
on unchanged code the change routes new inputs to is built from the changed `.java` files only; a change to a `.kt`
file adds nothing to it.

PIT drops the mutants of the Kotlin compiler-generated code it knows about, such as null checks on parameters; the
rest are reported as live mutants on a changed line whose text gives no hint. Arcmutate's
[Kotlin plugin](https://docs.arcmutate.com/docs/kotlin.html) filters more of them:
`-Pcribro.pitestPlugins=com.arcmutate:pitest-kotlin-plugin:<version>` in Gradle, or `-Dcribro.pitestPlugins=...` in
Maven, puts it on PIT's classpath, and it needs an Arcmutate licence file, `arcmutate-licence.txt`, at the repository
root; its page gives the terms.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
