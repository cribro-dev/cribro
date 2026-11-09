// Shared by every cribro project: a JDK 25 toolchain, class files for Java 11, and JUnit Jupiter on
// a JDK that CI picks.
plugins {
    `java-library`
    id("com.github.vlsi.gradle-extensions")
}

group = "io.github.cribrodev"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val libs = versionCatalogs.named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Every project publishes a javadoc jar; the comments document what needs it rather than every member.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// The Gradle plugin runs in a Gradle 8 daemon, which may run on JDK 11, the Maven extension in a
// Maven that cribro requires to run on JDK 11 or later, and PIT loads the interceptor into the JVM
// that runs the target build's tests. The PIT cribro resolves is compiled for Java 11.
tasks.compileJava {
    options.release = 11
}

// TestKit starts this build's own Gradle version, which needs JDK 17.
tasks.compileTestJava {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
    // CI picks the JDK that runs the tests, and extra options for the test JVM, from a random matrix
    // (.github/workflows/matrix.mjs). Without these properties the tests run on the compile toolchain.
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = providers.gradleProperty("testJavaVersion")
            .map { JavaLanguageVersion.of(it) }
            .orElse(java.toolchain.languageVersion)
        vendor = providers.gradleProperty("testJavaVendor")
            .map { JvmVendorSpec.matching(it) }
            .orElse(java.toolchain.vendor)
    }
    providers.gradleProperty("testExtraJvmArgs").orNull?.let { jvmArgs(it.split(Regex("\\s+")).filter(String::isNotEmpty)) }
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
}
