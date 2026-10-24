plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "org.aincrad"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pinned to the exact server build recorded in docs/ARCHITECTURE-DECISION.md.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("com.google.code.gson:gson:2.14.0")
}

tasks {
    compileJava {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("aincrad-plugin")
    }
    build {
        dependsOn(shadowJar)
    }
    test {
        useJUnitPlatform()
    }
}
