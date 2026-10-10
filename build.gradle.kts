import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("fabric-loom") version "1.17.19"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "org.odyssey"
version = providers.gradleProperty("mod_version")
    .orElse(providers.environmentVariable("ODYSSEY_VERSION"))
    .orElse("0.2.1-SNAPSHOT")
    .get()

val backendUrl = providers.gradleProperty("backend_url").orNull
    ?: throw GradleException("The required Gradle property 'backend_url' is missing")
val developmentBuild = providers.gradleProperty("odyssey_development")
    .map(String::toBoolean)
    .orElse(false)
val generatedSources = layout.buildDirectory.dir("generated/sources/odyssey/kotlin")

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

// Only the updater's portable runtime, not Minecraft or the Kotlin compiler.
val nativeTestRuntime by configurations.creating

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.3")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.13+kotlin.2.4.10")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    nativeTestRuntime("org.junit.platform:junit-platform-console-standalone:1.13.4")
    nativeTestRuntime("org.jetbrains.kotlin:kotlin-test-junit5:2.4.10") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    nativeTestRuntime("net.fabricmc:fabric-loader:0.19.3")
    // Fabric Loader's installer metadata supplies these; its Maven POM does not.
    listOf("asm", "asm-analysis", "asm-commons", "asm-tree", "asm-util").forEach {
        nativeTestRuntime("org.ow2.asm:$it:9.10.1")
    }
    nativeTestRuntime("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    nativeTestRuntime("org.slf4j:slf4j-simple:2.0.17")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget = JvmTarget.JVM_21
    sourceSets.main {
        kotlin.srcDir(generatedSources)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from("LICENSE") {
        rename { "LICENSE_odyssey" }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<RemapJarTask>("remapJar") {
    archiveFileName.set("odyssey-mod.jar")
}

val updateHelperJar by tasks.registering(Jar::class) {
    dependsOn(tasks.compileJava)
    archiveFileName.set("odyssey-update-helper.jar")
    destinationDirectory.set(layout.buildDirectory.dir("update-helper"))
    from(tasks.compileJava.flatMap { it.destinationDirectory })
    from("src/main/resources/odyssey-update.pub")
    manifest.attributes["Main-Class"] = "org.odyssey.mod.update.UpdateApplier"
}

tasks.processResources {
    dependsOn(updateHelperJar)
    from(updateHelperJar) { into("updates") }
}

tasks.test {
    dependsOn(updateHelperJar)
    systemProperty("odyssey.helper.jar", updateHelperJar.get().archiveFile.get().asFile.absolutePath)
}

val nativeTestBundle by tasks.registering(Zip::class) {
    dependsOn(tasks.testClasses, updateHelperJar)
    archiveFileName.set("native-updater-tests.zip")
    destinationDirectory.set(layout.buildDirectory.dir("native-tests"))
    from(sourceSets.main.get().output) {
        into("classes")
        include("org/odyssey/mod/update/**", "org/odyssey/mod/config/OdysseyConfig*",
            "org/odyssey/mod/OdysseyDiagnostics*", "odyssey-update.pub", "updates/**")
    }
    from(sourceSets.test.get().output) {
        into("classes")
        include("org/odyssey/mod/update/**")
    }
    from(nativeTestRuntime) { into("lib") }
}

tasks.register("ci") {
    dependsOn(tasks.build, nativeTestBundle)
}

val generateBackendConfig by tasks.registering {
    inputs.property("backend_url", backendUrl)
    inputs.property("development", developmentBuild)
    outputs.dir(generatedSources)
    doLast {
        val packageDir = generatedSources.get().dir("org/odyssey/mod/generated").asFile
        packageDir.mkdirs()
        packageDir.resolve("BuildConfig.kt").writeText(
            """
            package org.odyssey.mod.generated

            internal object BuildConfig {
                const val BACKEND_URL: String = ${backendUrl.toKotlinLiteral()}
                const val DEVELOPMENT: Boolean = ${developmentBuild.get()}
            }
            """.trimIndent() + "\n"
        )
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateBackendConfig)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

private fun String.toKotlinLiteral(): String = buildString {
    append('"')
    for (character in this@toKotlinLiteral) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}
