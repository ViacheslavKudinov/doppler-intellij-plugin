@file:JvmName("DopplerInstallerOptionsKt")

package com.vk.doppler.toolwindow

import java.util.Locale

internal data class LinuxInstallerOption(
    val distribution: String,
    val command: String,
)

internal fun linuxInstallerOption(osReleaseLines: List<String>): LinuxInstallerOption? {
    val osRelease =
        osReleaseLines
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator)
                val value =
                    line
                        .substring(separator + 1)
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                key to value
            }.toMap()

    val distribution = osRelease["ID"]?.lowercase(Locale.ROOT) ?: return null
    val version = osRelease["VERSION_ID"].orEmpty()
    return when (distribution) {
        "alpine" -> {
            LinuxInstallerOption(
                "Alpine Linux",
                "sudo wget -q -t3 'https://packages.doppler.com/public/cli/rsa.8004D9FF50437357.key' " +
                    "-O /etc/apk/keys/cli@doppler-8004D9FF50437357.rsa.pub && " +
                    "echo 'https://packages.doppler.com/public/cli/alpine/any-version/main' | " +
                    "sudo tee -a /etc/apk/repositories >/dev/null && sudo apk add doppler",
            )
        }

        "debian" -> {
            if (versionAtLeast(version, 11)) {
                LinuxInstallerOption("Debian", debianInstallCommand())
            } else {
                null
            }
        }

        "ubuntu" -> {
            if (versionAtLeast(version, 22, 4)) {
                LinuxInstallerOption("Ubuntu", debianInstallCommand())
            } else {
                null
            }
        }

        "rhel", "redhat", "centos", "amzn" -> {
            LinuxInstallerOption(
                distribution.replaceFirstChar { it.titlecase(Locale.ROOT) },
                "sudo rpm --import 'https://packages.doppler.com/public/cli/gpg.DE2A7741A397C129.key' && " +
                    "curl -sLf --retry 3 --tlsv1.2 --proto '=https' " +
                    "'https://packages.doppler.com/public/cli/config.rpm.txt' | " +
                    "sudo tee /etc/yum.repos.d/doppler-cli.repo >/dev/null && " +
                    "sudo yum update -y && sudo yum install -y doppler",
            )
        }

        else -> {
            null
        }
    }
}

private fun debianInstallCommand() =
    "sudo apt-get update && sudo apt-get install -y apt-transport-https ca-certificates curl gnupg && " +
        "curl -sLf --retry 3 --tlsv1.2 --proto '=https' " +
        "'https://packages.doppler.com/public/cli/gpg.DE2A7741A397C129.key' | " +
        "sudo gpg --batch --yes --dearmor -o /usr/share/keyrings/doppler-archive-keyring.gpg && " +
        "echo \"deb [signed-by=/usr/share/keyrings/doppler-archive-keyring.gpg] " +
        "https://packages.doppler.com/public/cli/deb/debian any-version main\" | " +
        "sudo tee /etc/apt/sources.list.d/doppler-cli.list >/dev/null && " +
        "sudo apt-get update && sudo apt-get install -y doppler"

private fun versionAtLeast(
    version: String,
    requiredMajor: Int,
    requiredMinor: Int = 0,
): Boolean {
    val parts = version.split('.')
    val major = parts.firstOrNull()?.toIntOrNull() ?: return false
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
}
