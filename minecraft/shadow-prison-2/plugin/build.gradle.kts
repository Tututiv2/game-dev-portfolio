plugins {
    java
}

group = "com.shadowprison"
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
    // Paper fetches the same coordinate at runtime via plugin.yml `libraries:` — keep identical.
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
        events("failed")
        showStandardStreams = false
    }
}

tasks.jar {
    archiveFileName.set("ShadowPrison2.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}
