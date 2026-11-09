// The Maven plugin: the goals, and a lifecycle participant that makes the same jar a Maven
// extension, loaded from .mvn/extensions.xml, from <extensions>true</extensions>, or with
// -Dmaven.ext.class.path.
plugins {
    id("cribro.java")
    alias(libs.plugins.maven.plugin.development)
    id("cribro.publish")
}

description = "Maven extension and goals that report the changed lines the tests do not execute (JaCoCo) or do not check (PIT)"

dependencies {
    implementation(project(":cribro-core"))
    implementation(libs.jacoco.agent)
    implementation(libs.jacoco.core)
    implementation(libs.jacoco.report)
    // Surefire's own filter for classpathDependencyExcludes. Maven exports SLF4J and the resolver
    // utilities to every realm, so the plugin does not carry its own copies.
    implementation(libs.maven.common.artifact.filters) {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.maven.resolver")
    }
    // Maven supplies its API to the plugin and extension realms; the descriptor and the POM must not
    // list it.
    compileOnly(libs.maven.core)
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.plugin.annotations)
    compileOnly(libs.maven.resolver.api)
    compileOnly(libs.javax.inject)
    // Writes META-INF/sisu/javax.inject.Named, the index Maven reads to find @Named components.
    annotationProcessor(libs.sisu.inject)
    testImplementation(libs.maven.core)
    testCompileOnly(libs.maven.plugin.annotations)
}

mavenPlugin {
    goalPrefix = "cribro"
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
        create<MavenPublication>("mavenPlugin") {
            from(components["java"])
            pom {
                packaging = "maven-plugin"
            }
        }
    }
}

// The sisu jar ships the processor without a META-INF/services entry, so javac has to be told its name.
tasks.compileJava {
    options.compilerArgs.addAll(listOf("-processor", "org.eclipse.sisu.space.SisuIndexAPT6"))
}

// -Dmaven.ext.class.path loads jars without their dependencies, so the plugin, core, JaCoCo, and ASM
// go into one jar.
val extensionJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Writes cribro-maven.jar, the plugin with its dependencies, for -Dmaven.ext.class.path."
    archiveFileName = "cribro-maven.jar"
    destinationDirectory = layout.buildDirectory.dir("dist")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
        exclude("META-INF/versions/*/module-info.class")
    }
}

// The descriptor task scans the sources of project dependencies, cribro-core's generated resources
// among them, without declaring the task that writes them.
tasks.named("generateMavenPluginDescriptor") {
    dependsOn(":cribro-core:versions")
}

// The integration tests run a real Maven, which resolves cribro from it-repository, and Maven
// Central for everything else.
val mavenDistribution = configurations.dependencyScope("mavenDistribution")
val mavenDistributionZip = configurations.resolvable("mavenDistributionZip") {
    extendsFrom(mavenDistribution.get())
    isTransitive = false
}

dependencies {
    mavenDistribution(variantOf(libs.apache.maven) { classifier("bin"); artifactType("zip") })
}

val unpackMaven by tasks.registering(Sync::class) {
    description = "Unpacks the Maven distribution the integration tests run."
    from({ zipTree(mavenDistributionZip.get().singleFile) }) {
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        filesMatching("bin/mvn") { permissions { unix("rwxr-xr-x") } }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("maven"))
}

tasks.test {
    dependsOn(
        unpackMaven,
        extensionJar,
        "publishAllPublicationsToItRepository",
        ":cribro-core:publishAllPublicationsToItRepository",
    )
    val mavenHome = layout.buildDirectory.dir("maven").get().asFile.absolutePath
    val repository = rootProject.layout.buildDirectory.dir("it-repository").get().asFile.absolutePath
    val localRepository = rootProject.layout.buildDirectory.dir("maven-it-local-repository").get().asFile.absolutePath
    val jar = layout.buildDirectory.file("dist/cribro-maven.jar").get().asFile.absolutePath
    systemProperty("cribro.it.mavenHome", mavenHome)
    systemProperty("cribro.it.repository", repository)
    systemProperty("cribro.it.localRepository", localRepository)
    systemProperty("cribro.it.extensionJar", jar)
    systemProperty("cribro.it.version", project.version.toString())
}
