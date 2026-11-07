plugins {
    `java-gradle-plugin`
    alias(libs.plugins.gradle.plugin.publish)
}

group = "io.github.cribrodev"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // The PIT interceptor and listener run inside PIT, which supplies these classes; the plugin
    // jar itself has no runtime dependency beyond the Gradle API.
    compileOnly(libs.pitest.entry)
    testImplementation(libs.pitest.entry)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// plugin-publish builds a javadoc jar; the comments document what needs it rather than every member.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
    // PIT loads the interceptor in the JVM that runs the tests, so 17 is also the floor for that JVM.
    options.release = 17
}

gradlePlugin {
    website = "https://github.com/cribro-dev/cribro"
    vcsUrl = "https://github.com/cribro-dev/cribro.git"
    plugins {
        create("cribro") {
            id = "io.github.cribrodev.cribro"
            implementationClass = "io.github.cribrodev.CribroPlugin"
            displayName = "cribro: diff coverage and diff mutation testing"
            description = "Reports which lines a branch changed that the tests do not execute (JaCoCo) " +
                "and which changed lines the tests do not check (PIT), as plain text for an AI agent"
            tags = listOf("coverage", "jacoco", "pitest", "mutation-testing", "diff-coverage")
        }
    }
}

val versions by tasks.registering(WriteProperties::class) {
    description = "Writes the PIT versions that the plugin reads at run time."
    destinationFile = layout.buildDirectory.file("generated/versions/io/github/cribrodev/cribro.properties")
    property("pitest", libs.versions.pitest.asProvider())
    property("pitestJunit5Plugin", libs.versions.pitest.junit5.plugin)
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/versions")).builtBy(versions))
}

// Writes the jar and the init script into one directory, so the init script finds the jar beside it.
val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Writes cribro.jar and cribro.init.gradle into build/dist, to copy into ~/.gradle/cribro."
    from(tasks.jar) { rename { "cribro.jar" } }
    from("src/dist")
    into(layout.buildDirectory.dir("dist"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
