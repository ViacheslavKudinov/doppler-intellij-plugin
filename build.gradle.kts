import java.util.Base64
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.file.RegularFile
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("jacoco")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.cyclonedx)
}

if (providers.gradleProperty("snapshot").isPresent) {
    version = "${project.version.toString().removeSuffix("-SNAPSHOT")}-SNAPSHOT"
}

kotlin {
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Build against the OLDEST supported IDE: plugin binaries are forward-compatible, not backward.
        intellijIdea("2025.3.6.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("JavaScript")
        bundledPlugin("NodeJS")
        bundledPlugin("Docker")
        bundledPlugin("org.jetbrains.idea.maven")
        plugin("PythonCore", "253.33813.55")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // No upper bound, so future IDE releases do not require a re-publish.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }

    signing {
        // Signing accepts base64, but signature verification expects a PEM certificate file.
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").map { value ->
            val certificate = value.trim()
            if (certificate.contains("-----BEGIN CERTIFICATE-----")) certificate
            else String(Base64.getDecoder().decode(certificate.filterNot(Char::isWhitespace)), Charsets.UTF_8)
        }
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
      token = providers.environmentVariable("PUBLISH_TOKEN")
  }
}

// Verify and upload the signed release asset without rebuilding or signing again.
val releaseArchive = providers.gradleProperty("releaseArchive").orNull
if (releaseArchive != null) {
    val verifyRelease = tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
        setDependsOn(emptyList<Any>())
        inputArchiveFile.set(layout.projectDirectory.file(releaseArchive))
        certificateChainFile.set(layout.projectDirectory.file(releaseArchive).asFile.resolveSibling("chain.crt"))
    }
    tasks.named<PublishPluginTask>("publishPlugin") {
        setDependsOn(listOf(verifyRelease))
        archiveFiles.setFrom(layout.projectDirectory.file(releaseArchive))
    }
} else {
    tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
        dependsOn(tasks.named("signPlugin"))
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        includes = listOf("com.vk.doppler.*")
    }
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeBuildEnvironment = true
    xmlOutput.convention(null as RegularFile?)
}

tasks.cyclonedxBom {
    projectType = Component.Type.APPLICATION
    jsonOutput = layout.buildDirectory.file("distributions/${project.name}-${project.version}.cdx.json")
    xmlOutput.convention(null as RegularFile?)
}

