# Gradle Mirror

A Kotlin JVM project that mirrors Gradle distributions by downloading them and uploading to a Nexus repository with optional git repository tagging.

## Features

- Downloads Gradle wrapper distributions from `https://services.gradle.org/distributions/`
- Supports both **bin** and **all** distribution types
- **Auto-detects latest Gradle version** from GitHub API when no version specified
- **Smart duplicate prevention** - checks git tags to avoid re-mirroring existing versions
- **Regex validation** for Gradle version format (e.g., 8.5, 8.4.1, 7.6-rc-1)
- Uploads to Nexus repository using maven-publish plugin
- Optional git repository tagging for tracking downloaded versions
- Configurable Gradle versions and distribution types

## Usage

### 1. Mirror Gradle Distribution

**Single Version:**
```bash
# Mirror latest version (auto-detected, defaults to bin distribution)
./gradlew mirrorGradle -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Mirror specific version with bin distribution
./gradlew mirrorGradle -PgradleVersion=8.5-bin -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Mirror with all distribution (includes sources and docs)
./gradlew mirrorGradle -PgradleVersion=8.5-all -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# With optional git remote URL for pushing tags
./gradlew mirrorGradle -PgradleVersion=8.5-bin -PgitRemoteUrl=https://github.com/your-org/repo.git -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
```

**Batch Mirroring (8.0 to latest):**
```bash
# Mirror all versions from 8.0 to latest (bin distribution)
./gradlew mirrorAllGradle -PfromVersion=8.0-bin -PdistType=bin -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Mirror all versions from 8.5 to latest (bin distribution)
./gradlew mirrorAllGradle -PfromVersion=8.5-bin -PdistType=bin -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Mirror all versions with 'all' distribution type
./gradlew mirrorAllGradle -PfromVersion=8.0-all -PdistType=all -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
```

**Valid gradleVersion formats:**
- `8.5-bin` (version with bin distribution)
- `8.4.1-all` (version with all distribution)
- `7.6-rc-1-bin` (version with qualifier and distribution)
- `8.5` (defaults to bin distribution)
- `` (empty - auto-detects latest version with bin distribution)

### 2. Manual Publishing (Optional)

The `mirrorGradle` task automatically publishes to Maven Local. To publish to Nexus:
```bash
./gradlew publish -PgradleVersion=8.5-bin -PnexusUrl=http://your-nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
```

## Task Details

### `mirrorGradle`

Downloads and publishes a single Gradle distribution:
- **Duplicate Check**: Checks git repository tags before mirroring to avoid duplicates
- **Auto-Detection**: Fetches latest version from GitHub API if no version specified
- **Fallback**: Uses version 8.5 if API call fails
- **URL Pattern**: `https://services.gradle.org/distributions/gradle-{version}-{type}.zip`
- **Distribution Types**: `bin` (runtime only) or `all` (includes sources & docs)
- **Version Validation**: Regex pattern `^\d+\.\d+(?:\.\d+)?(?:-\w+)?$`
- **Download**: Saves to `build/gradle-wrappers/gradle-{version}-{type}.zip`
- **Auto-Publish**: Automatically publishes to Maven Local repository
- **Git Tagging**: Creates a tag `gradle-{version}-{type}` in git repository (optionally pushed to remote)

### `mirrorAllGradle`

**Batch mirrors multiple Gradle versions from a starting version to latest:**
- **Version Range**: Mirrors all versions from `fromVersion` (default: 8.0) to latest
- **GitHub Integration**: Fetches all available versions from GitHub releases API
- **Smart Filtering**: Only processes stable versions, converts GitHub tags to Gradle format
- **Duplicate Prevention**: Skips versions that already exist (via git tag check)
- **Progress Tracking**: Reports mirrored, skipped, and failed counts
- **Same Features**: Uses same download, publish, and tagging logic as `mirrorGradle`

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
- (Optional) Git access for tag checking and repository tagging

## Example Workflow

```bash
# Single version mirroring
./gradlew mirrorGradle -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
./gradlew mirrorGradle -PgradleVersion=8.4-bin -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Batch mirroring - mirror ALL versions from 8.0 to latest
./gradlew mirrorAllGradle -PfromVersion=8.0-bin -PdistType=bin -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
./gradlew mirrorAllGradle -PfromVersion=8.5-bin -PdistType=bin -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
./gradlew mirrorAllGradle -PfromVersion=8.0-all -PdistType=all -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass

# Each version creates a separate git tag (e.g., gradle-8.4-bin, gradle-8.5-all)
# Subsequent runs will skip already-mirrored versions

# Optional: Publish to Nexus after mirroring
./gradlew publish -PgradleVersion=8.4-bin -PnexusUrl=http://nexus:8081/repository/maven-releases/ -PnexusUsername=user -PnexusPassword=pass
```