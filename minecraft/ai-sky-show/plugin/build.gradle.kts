plugins {
    java
}

group = "com.aisky"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// SkyClone is compiled against its BUILT jar, read-only. We never build SkyClone from here:
// another session owns that project. Override with -PskyCloneJar=<path> if it moves.
val skyCloneJar: String = (findProperty("skyCloneJar") as String?)
    ?: "../../SkyblockClone/plugin/build/libs/SkyClone.jar"

dependencies {
    // Same coordinate SkyClone builds against, so the API surfaces match exactly.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly(files(skyCloneJar))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.jar {
    archiveFileName.set("ShowControl.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}
