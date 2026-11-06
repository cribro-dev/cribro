plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.cribrodev"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// The jar compiles against pitest-entry, and the plugin resolves the same PIT for the target build.
val pitestVersion = "1.30.2"
val pitestJunit5PluginVersion = "1.2.3"

dependencies {
    // The PIT interceptor and listener run inside PIT, which supplies these classes; the plugin
    // jar itself has no runtime dependency beyond the Gradle API.
    compileOnly("org.pitest:pitest-entry:$pitestVersion")
    testImplementation("org.pitest:pitest-entry:$pitestVersion")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
    // PIT loads the interceptor in the JVM that runs the tests, so 17 is also the floor for that JVM.
    options.release = 17
}

gradlePlugin {
    plugins {
        create("cribro") {
            id = "io.github.cribrodev.cribro"
            implementationClass = "io.github.cribrodev.CribroPlugin"
        }
    }
}

val versions by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/versions/io/github/cribrodev/cribro.properties")
    property("pitest", pitestVersion)
    property("pitestJunit5Plugin", pitestJunit5PluginVersion)
}

sourceSets.main {
    resources.srcDir(versions.map { layout.buildDirectory.dir("generated/versions").get() })
}

// Writes the jar and the init script into one directory, so the init script finds the jar beside it.
val dist by tasks.registering(Sync::class) {
    from(tasks.jar) { rename { "cribro.jar" } }
    from("src/dist")
    into(layout.buildDirectory.dir("dist"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
