pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK 25 toolchain Minecraft 26.3 needs, so the
    // build does not depend on which JDK happens to launch Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcnet"

// The optional voice chat addon. It builds into its own jar and depends on
// the core mod at runtime, so players who do not want voice never load it.
include("voice")

// video/ is not a Gradle project: it is the Rust crate behind the voice
// addon's cameras, built by the voice build script (see voice/build.gradle.kts).
