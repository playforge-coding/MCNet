plugins {
    // Versions come from the root build script; Gradle allows a plugin
    // version to be declared only once per build.
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    id("com.diffplug.spotless")
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = "mcnet-voice"
}

repositories {
    mavenCentral()
    // opus4j is published by its author rather than to Maven Central.
    maven("https://maven.maxhenkel.de/repository/public") { name = "henkelmax" }
}

val coreSourceSets = project(":").extensions.getByType<SourceSetContainer>()

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mcnet-voice") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
        // Listing the core's source sets here is what lets `:voice:runClient`
        // see MCNet as a mod rather than as loose classes on the classpath.
        create("mcnet") {
            sourceSet(coreSourceSets["main"])
            sourceSet(coreSourceSets["client"])
        }
    }
}

// Dependencies nested into the addon jar. The iroh bindings and JNA are
// deliberately not here: the core mod already ships them, and Fabric loads
// every mod on one classpath, so a second copy would only add 29 MB.
val bundled: Configuration = configurations.create("bundled")

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // The core mod's compiled classes, straight from its source sets. Not the
    // core jar: that has iroh and JNA nested inside, and the loader would try
    // to load those nested jars a second time in dev. (Minecraft 26.x is
    // unobfuscated, so Loom no longer has a separate "named" configuration.)
    implementation(coreSourceSets["main"].output)
    "clientImplementation"(coreSourceSets["client"].output)

    // Compile-time and dev-run access to the iroh bindings the core bundles.
    // Same local-jar override as the core, so DHT builds work here too.
    val localIroh = providers.gradleProperty("mcnet.irohJar").orNull
    if (localIroh != null) {
        implementation(files(localIroh))
    } else {
        implementation("computer.iroh:iroh:${property("iroh_version")}") { isTransitive = false }
    }
    implementation("net.java.dev.jna:jna:${property("jna_version")}")

    // Opus. Its only declared dependency is a native loader that the published
    // jar already shades in, so transitivity is off to keep the nested set to
    // one jar.
    bundled("de.maxhenkel.opus4j:opus4j:${property("opus4j_version")}") { isTransitive = false }

    bundled.dependencies.forEach {
        implementation(it)
        include(it)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
        )
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.jar {
    from(rootProject.file("LICENSE")) { rename { "${it}_${base.archivesName.get()}" } }
}

val ktlintVersion = "1.8.0"

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
