plugins {
    id("cribro.java")
    `java-gradle-plugin`
    alias(libs.plugins.gradle.plugin.publish)
}

dependencies {
    // The PIT interceptor and listener run inside PIT, which supplies these classes; the plugin
    // jar itself has no runtime dependency beyond the Gradle API.
    compileOnly(libs.pitest.entry)
    testImplementation(libs.pitest.entry)
}

gradlePlugin {
    website = "https://github.com/cribro-dev/cribro"
    vcsUrl = "https://github.com/cribro-dev/cribro.git"
    plugins {
        // A settings plugin, so that one line in settings.gradle(.kts) covers every project, and so
        // that the init script can tell whether the build already applies it.
        create("cribro") {
            id = "io.github.cribrodev.cribro"
            implementationClass = "io.github.cribrodev.CribroSettingsPlugin"
            displayName = "cribro: diff coverage and diff mutation testing"
            description = "Reports which lines a branch changed that the tests do not execute (JaCoCo) " +
                "and which changed lines the tests do not check (PIT), as plain text for an AI agent"
            tags = listOf("coverage", "jacoco", "pitest", "mutation-testing", "diff-coverage")
        }
    }
}

val versions by tasks.registering(WriteProperties::class) {
    description = "Writes the cribro and PIT versions that the plugin reads at run time."
    destinationFile = layout.buildDirectory.file("generated/versions/io/github/cribrodev/cribro.properties")
    property("cribro", version.toString())
    property("pitest", libs.versions.pitest.asProvider())
    property("pitestJunit5Plugin", libs.versions.pitest.junit5.plugin)
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/versions")).builtBy(versions))
}

// The init script names the plugin version it resolves when no cribro.jar lies beside it.
val initScript by tasks.registering(Sync::class) {
    description = "Writes the init script with the plugin version filled in."
    from("src/dist")
    inputs.property("version", version.toString())
    filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("version" to version.toString()))
    into(layout.buildDirectory.dir("init-script"))
}

// Writes the jar and the init script into one directory, so the init script finds the jar beside it.
val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Writes cribro.jar and cribro.init.gradle into build/dist, to copy into ~/.gradle/cribro."
    from(tasks.jar) { rename { "cribro.jar" } }
    from(initScript)
    into(layout.buildDirectory.dir("dist"))
}

tasks.test {
    // The init script tests run a build with the jar and the init script laid out as dist leaves them.
    val distDirectory = files(dist)
    jvmArgumentProviders.add(objects.newInstance<DistArgument>().apply { this.dist.from(distDirectory) })
}

/** Passes the dist directory to the tests, keyed by its contents rather than by where the checkout lies. */
abstract class DistArgument : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dist: ConfigurableFileCollection

    override fun asArguments() = listOf("-Dcribro.dist=${dist.singleFile.absolutePath}")
}
