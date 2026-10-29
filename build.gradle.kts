import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.file.RegularFile
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import java.util.Base64

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Patch CycloneDX's transitive Jackson dependencies on the build classpath.
        classpath(platform(libs.jackson.bom))
        constraints {
            classpath(libs.guava.get()) {
                because("Fix GHSA-xxph-c9ww-hj94 in CycloneDX's transitive build dependency")
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin)
    id("jacoco")
    alias(libs.plugins.changelog)
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.cyclonedx)
}

val ktlint = configurations.dependencyScope("ktlint")
val ktlintClasspath =
    configurations.resolvable("ktlintClasspath") {
        extendsFrom(ktlint.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            // The external variant resolves Logback separately, so the security constraints below apply.
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
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
    add("ktlint", libs.ktlint.cli)
    constraints {
        add("ktlint", libs.logback.classic.get()) {
            because("Keep ktlint's Logback modules aligned with the patched logback-core version")
        }
        add("ktlint", libs.logback.core.get()) {
            because("Fix object injection through HardenedObjectInputStream in ktlint's transitive dependency")
        }
    }
    // Patch the IDE test framework without bundling Jackson in the plugin.
    add(
        "intellijPlatformTestDependencies",
        platform(libs.jackson.bom),
    )

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
        failureLevel.add(VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES)
        failureLevel.add(VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES)
        failureLevel.add(VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES)
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }

    signing {
        // Signing accepts base64, but signature verification expects a PEM certificate file.
        certificateChain =
            providers.environmentVariable("CERTIFICATE_CHAIN").map { value ->
                val certificate = value.trim()
                if (certificate.contains("-----BEGIN CERTIFICATE-----")) {
                    certificate
                } else {
                    String(Base64.getDecoder().decode(certificate.filterNot(Char::isWhitespace)), Charsets.UTF_8)
                }
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
    val verifyRelease =
        tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
            setDependsOn(emptyList<Any>())
            inputArchiveFile.set(layout.projectDirectory.file(releaseArchive))
            certificateChainFile.set(
                layout.projectDirectory
                    .file(releaseArchive)
                    .asFile
                    .resolveSibling("chain.crt"),
            )
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

/**
 * JVM options for the ktlint process. ktlint's bundled Kotlin compiler still uses `sun.misc.Unsafe`, which JDK 24+
 * reports with warnings (JEP 471/498). The opt-out flag only exists since JDK 23 and older JVMs refuse to start
 * with it, so it is added only for JDK 24+.
 */
abstract class KtlintJvmArguments : CommandLineArgumentProvider {
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    override fun asArguments(): List<String> {
        val javaVersion = launcher.get().metadata.languageVersion
        return buildList {
            add("--add-opens=java.base/java.lang=ALL-UNNAMED")
            if (javaVersion.canCompileOrRun(24)) add("--sun-misc-unsafe-memory-access=allow")
        }
    }
}

val ktlintSources =
    files(
        fileTree("src/main/kotlin") { include("**/*.kt") },
        fileTree("src/test/kotlin") { include("**/*.kt") },
        fileTree(".") { include("*.kts") },
    )
val ktlintPatterns = listOf("src/main/kotlin/**/*.kt", "src/test/kotlin/**/*.kt", "*.kts")

tasks.withType<JavaExec>().matching { it.name == "ktlintCheck" || it.name == "ktlintFormat" }.configureEach {
    classpath = ktlintClasspath.get()
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = layout.projectDirectory.asFile
    jvmArgumentProviders.add(objects.newInstance<KtlintJvmArguments>().also { it.launcher.set(javaLauncher) })
}

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks Kotlin sources and Gradle Kotlin scripts against the project's ktlint rules."
        val report = layout.buildDirectory.file("reports/ktlint/ktlint-check.xml")
        inputs.files(ktlintSources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(".editorconfig").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(report)
        args(listOf("--relative", "--reporter=plain", "--reporter=checkstyle,output=${report.get().asFile}") + ktlintPatterns)
    }

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Formats Kotlin sources and Gradle Kotlin scripts with the project's ktlint rules."
    args(listOf("--relative", "--format") + ktlintPatterns)
}

tasks.check {
    dependsOn(ktlintCheck)
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
