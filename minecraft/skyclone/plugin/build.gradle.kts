plugins {
    java
}

group = "com.skyclone"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Declared here so javac can see it; at runtime Paper fetches the same coordinate
    // via the `libraries:` block in plugin.yml. Keep the two versions identical.
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:-deprecation")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2G"
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

tasks.jar {
    archiveFileName.set("SkyClone.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
}

/**
 * Writes the compile classpath to build/classpath.txt so tools/check.sh can run a plain
 * javac per module. Thirty agents cannot share one gradle build directory at once; they
 * can each run their own javac into their own temp folder.
 */
tasks.register("writeClasspath") {
    val cp = configurations.compileClasspath
    val out = layout.buildDirectory.file("classpath.txt")
    doLast {
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(cp.get().files.joinToString(File.pathSeparator) { it.absolutePath })
    }
}
