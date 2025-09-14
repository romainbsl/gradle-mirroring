# Gradle Mirror

A Kotlin JVM project that mirrors Gradle distributions by downloading them and uploading to a Nexus repository with optional GitLab repository tagging.

## Features

- Downloads Gradle wrapper distributions from `https://services.gradle.org/distributions/`
- Supports both **bin** and **all** distribution types
- **Auto-detects latest Gradle version** from GitHub API when no version specified
- **Smart duplicate prevention** - checks GitLab tags to avoid re-mirroring existing versions
- **Regex validation** for Gradle version format (e.g., 8.5, 8.4.1, 7.6-rc-1)
- Uploads to Nexus repository using maven-publish plugin
- Optional GitLab repository tagging for tracking downloaded versions
- Configurable Gradle versions and distribution types

## Usage

### 1. Configure Properties

Copy `gradle-mirror.properties.example` to `gradle.properties` and update with your settings:

```properties
# Nexus Repository Configuration
nexusUrl=http://your-nexus-server:8081/repository/maven-releases/
nexusUsername=your-username
nexusPassword=your-password

# Gradle Download Configuration (gradleVersion is required)
# gradleVersion=8.5
distributionType=bin

# GitLab Configuration (optional - for tag checking and creation)
gitlab.url=https://your-gitlab-instance.com
gitlab.token=your-gitlab-api-token
gitlab.project.id=your-project-id
gitlab.branch=main
```

### 2. Mirror Gradle Distribution

**Single Version:**
```bash
# Mirror latest version (auto-detected)
./gradlew mirrorGradle

# Mirror specific version
./gradlew mirrorGradle -PgradleVersion=8.5

# Mirror with all distribution (includes sources and docs)
./gradlew mirrorGradle -PgradleVersion=8.5 -PdistributionType=all
```

**Batch Mirroring (8.0 to latest):**
```bash
# Mirror all versions from 8.0 to latest (bin distribution)
./gradlew mirrorAllGradle

# Mirror all versions from 8.5 to latest
./gradlew mirrorAllGradle -PfromVersion=8.5

# Mirror all versions with 'all' distribution type
./gradlew mirrorAllGradle -PdistributionType=all
```

**Valid version formats:**
- `8.5` (major.minor)
- `8.4.1` (major.minor.patch)  
- `7.6-rc-1` (with qualifier)

### 3. Manual Publishing (Optional)

The `mirrorGradle` task automatically publishes to Maven Local. To publish to Nexus:
```bash
./gradlew publish -PgradleVersion=8.5 -PdistributionType=bin
```

**Note:** The `downloadGradleWrapper` task is deprecated but maintained for backwards compatibility.

## Task Details

### `mirrorGradle`

Downloads and publishes a single Gradle distribution:
- **Duplicate Check**: Checks GitLab repository tags before mirroring to avoid duplicates
- **Auto-Detection**: Fetches latest version from GitHub API if no version specified
- **Fallback**: Uses version 8.5 if API call fails
- **URL Pattern**: `https://services.gradle.org/distributions/gradle-{version}-{type}.zip`
- **Distribution Types**: `bin` (runtime only) or `all` (includes sources & docs)
- **Version Validation**: Regex pattern `^\d+\.\d+(?:\.\d+)?(?:-\w+)?$`
- **Download**: Saves to `build/gradle-wrappers/gradle-{version}-{type}.zip`
- **Auto-Publish**: Automatically publishes to Maven Local repository
- **GitLab Tagging**: Creates a tag `gradle-{version}-{type}` in remote GitLab repository

### `mirrorAllGradle`

**Batch mirrors multiple Gradle versions from a starting version to latest:**
- **Version Range**: Mirrors all versions from `fromVersion` (default: 8.0) to latest
- **GitHub Integration**: Fetches all available versions from GitHub releases API
- **Smart Filtering**: Only processes stable versions, converts GitHub tags to Gradle format
- **Duplicate Prevention**: Skips versions that already exist (via GitLab tag check)
- **Progress Tracking**: Reports mirrored, skipped, and failed counts
- **Same Features**: Uses same download, publish, and tagging logic as `mirrorGradle`

### `downloadGradleWrapper` (Deprecated)

Legacy task maintained for backwards compatibility. Use `mirrorGradle` instead.

### Maven Publication

The project publishes artifacts with:
- **GroupId**: `org.gradle`
- **ArtifactId**: `gradle-{version}-{type}` (e.g., `gradle-8.5-bin`, `gradle-8.4-all`)
- **Version**: Gradle version (e.g., `8.5`, `8.4`, `9.0`)
- **Extension**: `zip`

**Example artifacts:**
- `org.gradle:gradle-8.5-bin:8.5@zip` (bin distribution)
- `org.gradle:gradle-8.4-all:8.4@zip` (all distribution)

**Maven repository structure:**
```
org/gradle/gradle-8.5-bin/8.5/gradle-8.5-bin-8.5.zip    (bin)
org/gradle/gradle-8.4-all/8.4/gradle-8.4-all-8.4.zip    (all)
```

**Note:** These are downloadable binary distributions, not Maven/Gradle dependencies.

## Requirements

- Java 17 or higher
- Gradle 8.5 or higher
- Access to Nexus repository for publishing
- (Optional) GitLab API access for tag checking and repository tagging

## Example Workflow

```bash
# Single version mirroring
./gradlew mirrorGradle                                              # Latest version
./gradlew mirrorGradle -PgradleVersion=8.4 -PdistributionType=bin   # Specific version

# Batch mirroring - mirror ALL versions from 8.0 to latest
./gradlew mirrorAllGradle                                           # 27 versions (8.0 to 9.0)
./gradlew mirrorAllGradle -PfromVersion=8.5                        # Subset (8.5 to 9.0)
./gradlew mirrorAllGradle -PdistributionType=all                   # All versions with 'all' dist

# Each version creates a separate GitLab tag (e.g., gradle-8.4-bin, gradle-8.5-all)
# Subsequent runs will skip already-mirrored versions

# Optional: Publish to Nexus after mirroring
./gradlew publish -PgradleVersion=8.4 -PdistributionType=bin
```