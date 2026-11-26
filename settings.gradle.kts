import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "doppler-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:2.20.1")
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.23.0"))
        constraints {
            classpath("org.jsoup:jsoup:1.23.2") {
                because("Fix vulnerabilities in the IntelliJ Platform Gradle plugin's transitive jsoup dependency")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

apply(plugin = "org.jetbrains.intellij.platform.settings")

dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
