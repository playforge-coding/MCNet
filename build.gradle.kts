plugins {
    id("net.fabricmc.fabric-loom") version "1.19.1"
    kotlin("jvm") version "2.4.21"
    id("com.diffplug.spotless") version "8.10.4"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    mavenCentral()
    // DevAuth is published by its author rather than to Maven Central.
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") { name = "devauth" }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mcnet") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        named("client") {
            // -Pmcnet.account=<name> signs the dev client in through DevAuth
            // as that account. Without it the client starts as an offline
            // "PlayerNNN" and cannot join a hosted world. See the README.
            val account = providers.gradleProperty("mcnet.account").orNull
            if (account != null) {
                property("devauth.enabled", "true")
                property("devauth.account", account)
            }
        }
    }
}

// Dependencies that must travel inside the built jar, because they are not
// shipped by Minecraft, Fabric Loader or fabric-language-kotlin.
val bundled: Configuration = configurations.create("bundled")

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Provides the `kotlin` language adapter used by fabric.mod.json, plus the
    // Kotlin stdlib and kotlinx-coroutines at runtime.
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // iroh's Kotlin bindings. Transitivity is switched off deliberately: the
    // artifact asks for its own kotlin-stdlib and kotlinx-coroutines, which
    // fabric-language-kotlin already puts on the runtime classpath at a newer
    // version. JNA is pulled in explicitly below instead.
    //
    // -Pmcnet.irohJar=/path/to/iroh.jar swaps in locally built bindings, which
    // is how DHT support is tested before it is published. See dht/README.md.
    val localIroh = providers.gradleProperty("mcnet.irohJar").orNull
    if (localIroh != null) {
        // Development override only: it goes on the compile and runtime
        // classpath but is not nested into the mod jar, because Jar-in-Jar
        // needs a maven coordinate to generate metadata from.
        logger.lifecycle("MCNet: using local iroh bindings from $localIroh (not bundled)")
        implementation(files(localIroh))
    } else {
        bundled("computer.iroh:iroh:${property("iroh_version")}") { isTransitive = false }
    }
    bundled("net.java.dev.jna:jna:${property("jna_version")}")

    bundled.dependencies.forEach {
        implementation(it)
        include(it)
    }

    // Dev client sign-in. Runtime only, so it is never compiled against or
    // nested into the jar.
    runtimeOnly("me.djtheredstoner:DevAuth-fabric:${property("devauth_version")}")

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
    from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
}

// ktlint lints and formats. Spotless hooks spotlessCheck into `check`, so a
// plain `./gradlew build` fails on unformatted code; `./gradlew spotlessApply`
// fixes it. Rule tweaks live in .editorconfig, where IDEs pick them up too.
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
