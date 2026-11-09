// Git diff parsing, JaCoCo XML reading, report rendering, and the PIT interceptor and listener:
// what the Gradle and Maven plugins share, with no Gradle or Maven API.
plugins {
    id("cribro.java")
    id("cribro.publish")
}

description = "Git diff parsing, JaCoCo report reading, and the PIT interceptor shared by the cribro Gradle and Maven plugins"

dependencies {
    // PIT supplies these classes when it loads the interceptor; the jar has no runtime dependency.
    compileOnly(libs.pitest.entry)
    testImplementation(libs.pitest.entry)
}

// The plugins read these at run time, and the Gradle plugin's jar carries this file with the classes.
val versions by tasks.registering(WriteProperties::class) {
    description = "Writes the cribro, PIT, and JaCoCo versions that the plugins read at run time."
    destinationFile = layout.buildDirectory.file("generated/versions/io/github/cribrodev/cribro.properties")
    property("cribro", version.toString())
    property("pitest", libs.versions.pitest.asProvider())
    property("pitestJunit5Plugin", libs.versions.pitest.junit5.plugin)
    property("jacoco", libs.versions.jacoco)
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/versions")).builtBy(versions))
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    // A local repository the Maven tests resolve cribro from, so they need nothing in ~/.m2.
    repositories {
        maven {
            name = "it"
            url = uri(rootProject.layout.buildDirectory.dir("it-repository"))
        }
    }
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
    }
}
