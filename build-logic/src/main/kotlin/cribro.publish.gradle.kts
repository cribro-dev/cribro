// Shared by the projects published to Maven Central: the POM metadata that Central requires, the
// signatures, and the nmcp variant that the root project uploads (see cribro.central).
plugins {
    id("cribro.signing")
    id("com.gradleup.nmcp")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = project.name
            description = provider { project.description }
            url = "https://github.com/cribro-dev/cribro"
            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0"
                }
            }
            developers {
                developer {
                    id = "vlsi"
                    name = "Vladimir Sitnikov"
                }
            }
            scm {
                connection = "scm:git:https://github.com/cribro-dev/cribro.git"
                developerConnection = "scm:git:https://github.com/cribro-dev/cribro.git"
                url = "https://github.com/cribro-dev/cribro"
            }
        }
    }
}
