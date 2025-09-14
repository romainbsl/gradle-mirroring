import org.gradle.kotlin.dsl.provideDelegate
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

tasks.register<MirrorGradleTask>("mirrorGradle") {
    group = "gradle-mirror"
    description = "Downloads Gradle distribution and publishes to Nexus repository"

    targetVersion.set(gradleVersion)
    outputDir.convention(layout.buildDirectory.dir("gradle-wrappers"))
}

tasks.register<MirrorAllGradleTask>("mirrorAllGradle") {
    group = "gradle-mirror"
    description = "Mirrors all Gradle versions from 8.0 to latest"

    val projectFromVersion = project.findProperty("fromVersion") as? String ?: "8.0-bin"
    val projectDistType = project.findProperty("distType") as? String ?: "bin"

    fromVersion.set(projectFromVersion)
    distType.set(projectDistType)
    outputDir.convention(layout.buildDirectory.dir("gradle-wrappers"))
}


// Shared utility object
object GradleMirrorUtils {
    fun checkIfVersionExists(project: Project, gradleVersion: String): Boolean {
        return try {
            val tagName = "gradle-$gradleVersion"
            
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
    
    fun tagGitRepository(project: Project, gradleVersion: String) {
        try {
            val gitRemoteUrl = project.findProperty("gitRemoteUrl") as? String
            val tagName = "gradle-$gradleVersion"
            val tagMessage = "Gradle $gradleVersion distribution mirrored"
            
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
    
    fun publishToMavenLocal(project: Project, gradleVersion: String) {
        try {
            println("Publishing gradle-$gradleVersion.zip to Maven Local...")

            val processBuilder = ProcessBuilder(
                "./gradlew", "publishToMavenLocal",
                "-PgradleVersion=$gradleVersion"
            )
            processBuilder.directory(project.rootDir)
            processBuilder.inheritIO()
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("Publish command failed with exit code $exitCode")
            }
            
            println("✅ Successfully published org.gradle:gradle:$gradleVersion@zip to Maven Local")
            println("Run './gradlew publish -PgradleVersion=$gradleVersion' to publish to Nexus")

        } catch (e: Exception) {
            println("❌ Failed to publish: ${e.message}")
            println("You can manually run: ./gradlew publish -PgradleVersion=$gradleVersion")
        }
    }
}

abstract class MirrorGradleTask : DefaultTask() {
    @get:Input
    abstract val targetVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun mirrorGradle() {
        val inputVersion = targetVersion.get()

        // Validate and normalize gradle version format
        val gradleVersion = if (inputVersion.isBlank()) {
            // Auto-detect latest version
            println("No gradleVersion specified, fetching latest version...")
            val latestVersion = getLatestGradleVersion()
            "$latestVersion-bin"
        } else {
            // Validate full version format: 8.5-bin, 8.4.1-all, 7.6-rc-1-bin, 8.5
            val gradleVersionRegex = Regex("""^(\d+\.\d+(?:\.\d+)?(?:-\w+)?)(?:-(bin|all))?$""")
            val matchResult = gradleVersionRegex.find(inputVersion)
                ?: throw IllegalArgumentException("Invalid gradleVersion format: '$inputVersion'. Valid formats: 8.5-bin, 8.4.1-all, 7.6-rc-1-bin, 8.5")

            val version = matchResult.groupValues[1]
            val distType = matchResult.groupValues[2].ifEmpty { "bin" }
            "$version-$distType"
        }

        val downloadUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion.zip"
        val outputFile = outputDir.get().file("gradle-$gradleVersion.zip").asFile
        
        outputFile.parentFile.mkdirs()
        
        // Check if version already exists in git tags
        if (GradleMirrorUtils.checkIfVersionExists(project, gradleVersion)) {
            println("⏭️ Gradle $gradleVersion already mirrored (tag exists), skipping...")
            return
        }

        println("Downloading Gradle $gradleVersion from $downloadUrl")
        
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
        val publishFile = project.file("gradle-$gradleVersion.zip")
        outputFile.copyTo(publishFile, overwrite = true)

        // Tag git repository
        GradleMirrorUtils.tagGitRepository(project, gradleVersion)

        // Automatically publish to Maven Local
        GradleMirrorUtils.publishToMavenLocal(project, gradleVersion)
    }
    
    private fun getLatestGradleVersion(): String {
        return try {
            println("Fetching latest Gradle version from GitHub API...")
            
            @Suppress("DEPRECATION")
            val url = URL("https://api.github.com/repos/gradle/gradle/releases/latest")
            val connection = url.openConnection()
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "gradle-mirror")
            
            val response = connection.getInputStream().bufferedReader().use { reader ->
                reader.readText()
            }
            
            // Parse JSON response to extract version
            // Look for "tag_name": "vX.Y.Z" pattern
            val versionRegex = Regex(""""tag_name":\s*"v?([^"]+)"""")
            val matchResult = versionRegex.find(response)
            
            val version = matchResult?.groupValues?.get(1)
                ?: throw RuntimeException("Could not parse version from GitHub API response")
            
            println("Latest Gradle version: $version")
            version
            
        } catch (e: Exception) {
            println("Warning: Failed to fetch latest version (${e.message}), falling back to 8.5")
            "8.5"
        }
    }
}

abstract class MirrorAllGradleTask : DefaultTask() {
    @get:Input
    abstract val fromVersion: Property<String>

    @get:Input
    abstract val distType: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun mirrorAllGradle() {
        val fromVer = fromVersion.get()
        val distributionType = distType.get()

        println("🚀 Starting batch mirroring from Gradle $fromVer to latest ($distributionType distribution)")

        // Get all available Gradle versions from GitHub
        val allVersions = getAllGradleVersions()

        // Filter versions from the specified version onwards
        val versionsToMirror = filterVersionsFrom(allVersions, fromVer)

        println("📋 Found ${versionsToMirror.size} versions to mirror: ${versionsToMirror.joinToString(", ")}")

        var mirrored = 0
        var skipped = 0
        var failed = 0

        versionsToMirror.forEach { version ->
            try {
                println("\n--- Processing Gradle $version ---")

                val gradleVersion = "$version-$distributionType"
                if (GradleMirrorUtils.checkIfVersionExists(project, gradleVersion)) {
                    println("⏭️ Gradle $gradleVersion already mirrored (tag exists), skipping...")
                    skipped++
                    return@forEach
                }

                // Mirror this version
                mirrorVersion(gradleVersion)
                mirrored++
                println("✅ Successfully mirrored Gradle $gradleVersion")

            } catch (e: Exception) {
                println("❌ Failed to mirror Gradle $version: ${e.message}")
                failed++
            }
        }

        println("\n🎉 Batch mirroring completed!")
        println("   ✅ Mirrored: $mirrored versions")
        println("   ⏭️ Skipped: $skipped versions")
        println("   ❌ Failed: $failed versions")
    }

    private fun getAllGradleVersions(): List<String> {
        return try {
            println("Fetching all Gradle versions from GitHub API...")

            @Suppress("DEPRECATION")
            val url = URL("https://api.github.com/repos/gradle/gradle/releases?per_page=100")
            val connection = url.openConnection()
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "gradle-mirror")

            val response = connection.getInputStream().bufferedReader().use { reader ->
                reader.readText()
            }

            // Parse JSON response to extract all version numbers
            val versionRegex = Regex(""""tag_name":\s*"v?([^"]+)"""")
            val versions = versionRegex.findAll(response)
                .map { it.groupValues[1] }
                .filter { it.matches(Regex("""^\d+\.\d+(\.\d+)?$""")) } // Only stable versions (no .0 suffix handling)
                .map { version ->
                    // Convert 8.6.0 to 8.6 format to match Gradle distribution URLs
                    if (version.endsWith(".0") && version.count { it == '.' } == 2) {
                        version.substringBeforeLast(".0")
                    } else {
                        version
                    }
                }
                .distinct()
                .toList()

            println("Found ${versions.size} Gradle versions")
            versions

        } catch (e: Exception) {
            println("Warning: Failed to fetch versions (${e.message}), using fallback list")
            // Fallback list of known versions
            listOf("8.0", "8.0.1", "8.0.2", "8.1", "8.1.1", "8.2", "8.2.1", "8.3", "8.4", "8.5", "8.6", "8.7", "8.8", "8.9", "8.10", "9.0")
        }
    }

    private fun filterVersionsFrom(allVersions: List<String>, fromVersion: String): List<String> {
        // Parse version numbers for comparison
        fun parseVersion(version: String): List<Int> {
            return version.split(".").map { it.toIntOrNull() ?: 0 }
        }

        val fromVersionParts = parseVersion(fromVersion)

        return allVersions
            .filter { version ->
                val versionParts = parseVersion(version)
                compareVersions(versionParts, fromVersionParts) >= 0
            }
            .sortedWith { a, b ->
                val aParts = parseVersion(a)
                val bParts = parseVersion(b)
                compareVersions(aParts, bParts)
            }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val maxLength = maxOf(a.size, b.size)
        for (i in 0 until maxLength) {
            val aVal = a.getOrNull(i) ?: 0
            val bVal = b.getOrNull(i) ?: 0
            val comparison = aVal.compareTo(bVal)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun mirrorVersion(gradleVersion: String) {
        val downloadUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion.zip"
        val outputFile = outputDir.get().file("gradle-$gradleVersion.zip").asFile

        outputFile.parentFile.mkdirs()

        // Download
        @Suppress("DEPRECATION")
        val url = URL(downloadUrl)
        url.openStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }

        // Copy to root for publishing
        val publishFile = project.file("gradle-$gradleVersion.zip")
        outputFile.copyTo(publishFile, overwrite = true)

        // Tag git repository
        GradleMirrorUtils.tagGitRepository(project, gradleVersion)

        // Publish to Maven Local
        GradleMirrorUtils.publishToMavenLocal(project, gradleVersion)
    }
}
