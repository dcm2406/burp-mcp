plugins {
    java
}

group = "io.github.burpmcp"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    // Session-scoped proxy history metadata index (HistoryIndex), bundled in the fat JAR.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The integration tests bind loopback ports and write per-test audit files;
    // parallel forks make them flaky for no real speed-up.
    maxParallelForks = 1
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.add("-Xlint:all")
}

tasks.jar {
    archiveBaseName = "burp-mcp-extension"
    manifest {
        attributes(
            "Implementation-Title" to "Burp MCP Extension",
            "Implementation-Version" to project.version
        )
    }
}

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the Burp extension with runtime dependencies but without Montoya API."
    archiveBaseName = "burp-mcp-extension"
    archiveVersion = project.version.toString()
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes(
            "Implementation-Title" to "Burp MCP Extension",
            "Implementation-Version" to project.version
        )
    }
}

tasks.assemble {
    dependsOn(fatJar)
}
