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
        classpath(platform("io.netty:netty-bom:4.2.17.Final"))
        constraints {
            classpath("org.jsoup:jsoup:1.24.1") {
                because("Fix vulnerabilities in the IntelliJ Platform Gradle plugin's transitive jsoup dependency")
            }
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") {
                because(
                    "Fix the certificate name-constraints bypass in the IntelliJ Platform Gradle plugin's transitive Bouncy Castle dependency",
                )
            }
            classpath("com.google.guava:guava:33.8.0-jre") {
                because("Fix vulnerabilities in the IntelliJ Platform Gradle plugin's transitive Guava dependency")
            }
            classpath("com.google.protobuf:protobuf-java:3.25.5") {
                because("Fix a denial-of-service vulnerability in the IntelliJ Platform Gradle plugin's transitive Protobuf dependency")
            }
        }
    }
    configurations.classpath {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.lz4:lz4-java"))
                .using(module("at.yawk.lz4:lz4-java:1.14.1"))
                .because("Use the maintained LZ4 fork with fixes for vulnerabilities in the original artifact")
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
