plugins {
    java
}

group = "com.thestill"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    // Declared here so javac can see it; at runtime Paper fetches the same coordinate
    // via the `libraries:` block in plugin.yml. Keep the two versions identical.
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    archiveFileName.set("TheStill.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}
