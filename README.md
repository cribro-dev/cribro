# cribro Gradle plugin

Reports which lines a branch changed that the tests do not execute (JaCoCo) and which changed lines the tests do not
check (PIT), for a Gradle build with Java sources, without changing that build. The output is plain text sized for an AI agent;
there is no HTML report of its own.

The code started in NullAway's `buildSrc` ([uber/NullAway#1833](https://github.com/uber/NullAway/pull/1833) and
[uber/NullAway#1975](https://github.com/uber/NullAway/pull/1975)), written by the same author.

## Requirements

- Gradle 8.0 or later, running on JDK 17 or later. Nothing older than 8.14 has been tried.
- Tests that run on JDK 17 or later, since PIT loads this plugin's classes into the test JVM.
- Java sources under the `java` plugin's source sets; Kotlin and Groovy sources are not measured.
- JUnit 4, or JUnit Jupiter or Vintage on the JUnit Platform, for `pitestDiff`.

Tried on pgjdbc (Gradle 9.7.1, JUnit Jupiter), NullAway (Gradle 9.8.1, JUnit 4), and a one-class build on Gradle
8.14.3 whose tests have no `junit-platform-launcher` dependency.

## Install

```bash
./gradlew --quiet dist
```

```bash
mkdir -p ~/.gradle/cribro && cp build/dist/* ~/.gradle/cribro/
```

The init script loads `cribro.jar` from its own directory. The plugin jar has no runtime dependencies: PIT is
resolved from the target build's repositories when `pitestDiff` runs.

## Use

```bash
./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle :<project>:test
```

```bash
./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle :<project>:pitestDiff -Pcribro.pitestTargetTests=<tests>
```

To tell an agent about the tool, install `agent/cribro/SKILL.md` as a skill
(`~/.claude/skills/cribro/SKILL.md` for Claude Code). It lists the tasks, the properties, and how to act on each
report.

## What the plugin does to a build

For every project with the `java` plugin, the plugin:

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
