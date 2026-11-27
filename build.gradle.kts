import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.svenbluege"
version = "0.2.0"

// IntelliJ Platform 2026.2 is compiled for Java 25.
kotlin { jvmToolchain(25) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // -PlocalIdePath=<IDE install dir> (or the same line in ~/.gradle/gradle.properties) skips the IDE download.
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else webstorm("2026.2.1")
        bundledPlugin("com.intellij.modules.jcef") // the embedded browser is a separate bundled plugin since 2026.2
        testFramework(TestFrameworkType.Platform) // for PipelineEditorTest, which starts a headless IDE
    }
    // Bundled on purpose: the IDE's own SnakeYAML is a platform-internal module, not visible to plugins.
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    instrumentCode = false // Kotlin only: nothing to instrument
    pluginConfiguration {
        ideaVersion { untilBuild = provider { null } }
    }
}
