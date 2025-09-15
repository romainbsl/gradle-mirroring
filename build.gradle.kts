import org.gradle.kotlin.dsl.provideDelegate
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion.Companion.fromVersion
import java.io.FileOutputStream
import java.net.URL

plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
}

repositories {
    mavenCentral()
}

// No runtime dependencies needed

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

val nexusUrl: String? by project
val nexusUsername: String? by project  
val nexusPassword: String? by project

val gradleVersion: String? by project
val distType: String? by project
val gitRemoteUrl: String? by project

publishing {
    publications {
        create<MavenPublication>("gradle-wrapper") {
            groupId = "org.gradle"
            
            afterEvaluate {
                val gradleVersionFull = gradleVersion ?: return@afterEvaluate

                artifactId = "gradle"
                version = gradleVersionFull

                val wrapperFile = file("gradle-$gradleVersionFull.zip")
                if (wrapperFile.exists()) {
                    artifact(wrapperFile) {
                        extension = "zip"
                    }
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "nexus"
            url = uri(nexusUrl ?: "http://localhost:8081/repository/maven-releases/")
            isAllowInsecureProtocol = true
            credentials {
                username = nexusUsername ?: ""
                password = nexusPassword ?: ""
            }
        }
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<MirrorGradleTask>())
}

tasks.register<MirrorGradleTask>("mirrorGradle") {
    group = "gradle-mirror"
    description = "Downloads Gradle distribution and publishes to Nexus repository"

    inputGradleVersion.set(gradleVersion)
    inputDistType.set(distType)
    outputDir.convention(layout.buildDirectory.dir("gradle-wrappers"))
}

object GradleMirrorUtils {
    fun checkIfVersionExists(project: Project, version: String, distType: String): Boolean {
        return try {
            val tagName = "gradle-$version-$distType"
            
            // Check if tag exists locally using git command
            val process = ProcessBuilder("git", "tag", "-l", tagName)
                .directory(project.rootDir)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            
            val exists = exitCode == 0 && output.isNotEmpty()
            
            if (exists) {
                println("✅ Tag '$tagName' exists locally")
            } else {
                println("❌ Tag '$tagName' does not exist locally")
            }
            
            exists
        } catch (e: Exception) {
            println("Warning: Could not check git tags: ${e.message}")
            false
        }
    }
    
    fun tagGitRepository(project: Project, version: String, distType: String) {
        try {
            val gitRemoteUrl = project.findProperty("gitRemoteUrl") as? String
            val tagName = "gradle-$version-$distType"
            val tagMessage = "Gradle $version $distType distribution mirrored"
            
            println("Creating git tag '$tagName'...")
            
            // Create tag locally
            val createProcess = ProcessBuilder("git", "tag", "-a", tagName, "-m", tagMessage)
                .directory(project.rootDir)
                .start()
            
            val createExitCode = createProcess.waitFor()
            if (createExitCode != 0) {
                val error = createProcess.errorStream.bufferedReader().use { it.readText() }
                println("❌ Failed to create git tag: $error")
                return
            }
            
            println("✅ Successfully created git tag: $tagName")
            
            // Push tag to remote if URL provided
            if (gitRemoteUrl != null) {
                println("Pushing tag to remote: $gitRemoteUrl")
                val pushProcess = ProcessBuilder("git", "push", "origin", tagName)
                    .directory(project.rootDir)
                    .start()
                
                val pushExitCode = pushProcess.waitFor()
                if (pushExitCode == 0) {
                    println("✅ Successfully pushed tag to remote")
                } else {
                    val error = pushProcess.errorStream.bufferedReader().use { it.readText() }
                    println("❌ Failed to push tag to remote: $error")
                }
            }
            
        } catch (e: Exception) {
            println("Warning: Failed to tag git repository: ${e.message}")
        }
    }
}

abstract class MirrorGradleTask : DefaultTask() {
    @get:Input
    abstract val inputGradleVersion: Property<String>

    @get:Input
    abstract val inputDistType: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun mirrorGradle() {
        val version = inputGradleVersion.get()
        val distType = inputDistType.get()

        // Validate that both parameters are provided
        if (version.isBlank()) {
            throw IllegalArgumentException("gradleVersion is required. Use -PgradleVersion=8.5")
        }
        if (distType.isBlank()) {
            throw IllegalArgumentException("distType is required. Use -PdistType=bin or -PdistType=all")
        }

        // Validate version format against proper Gradle version regex
        val versionRegex = Regex("""^\d+\.\d+(?:\.\d+)?(?:-\w+)*$""")
        if (!versionRegex.matches(version)) {
            throw IllegalArgumentException("Invalid Gradle version format: '$version'. Expected format: x.y[.z][-qualifier] (e.g., 8.5, 8.4.1, 7.6-rc-1)")
        }

        // Validate distribution type
        if (distType !in listOf("bin", "all")) {
            throw IllegalArgumentException("Invalid distribution type: '$distType'. Must be 'bin' or 'all'")
        }

        val downloadUrl = "https://services.gradle.org/distributions/gradle-$version-$distType.zip"
        val outputFile = outputDir.get().file("gradle-$version-$distType.zip").asFile

        outputFile.parentFile.mkdirs()

        // Check if version already exists in git tags
        if (GradleMirrorUtils.checkIfVersionExists(project, version, distType)) {
            println("⏭️ Gradle $version-$distType already mirrored (tag exists), skipping...")
            return
        }

        println("Downloading Gradle $version-$distType from $downloadUrl")
        
        // Download using java.net.URI/URL
        @Suppress("DEPRECATION")
        val url = URL(downloadUrl)
        url.openStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        
        println("Downloaded to ${outputFile.absolutePath}")

        // Copy to root for publishing
        val publishFile = project.file("gradle-$version-$distType.zip")
        outputFile.copyTo(publishFile, overwrite = true)

        // Tag git repository
        GradleMirrorUtils.tagGitRepository(project, version, distType)
    }
}
