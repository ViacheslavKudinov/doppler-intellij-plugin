package com.vk.doppler.toolwindow

import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

internal data class ReviewedInstaller(
    val path: Path,
    val sha256: String,
)

private const val INSTALLER_TEMP_PREFIX = "doppler-installer-"
private const val INSTALLER_TEMP_SUFFIX = ".sh"
internal const val MAX_INSTALLER_BYTES = 2 * 1024 * 1024
internal const val INSTALL_SCRIPT_URL = "https://cli.doppler.com/install.sh"
internal const val INSTALL_SCRIPT_SOURCE_URL =
    "https://raw.githubusercontent.com/DopplerHQ/cli/master/scripts/install.sh"

/** Returns a URL built from a trusted constant, never from the remote redirect value. */
internal fun trustedInstallerUrl(candidate: URI): URL {
    val trusted =
        when (candidate.normalize().toASCIIString()) {
            INSTALL_SCRIPT_URL -> INSTALL_SCRIPT_URL
            INSTALL_SCRIPT_SOURCE_URL -> INSTALL_SCRIPT_SOURCE_URL
            else -> throw IllegalArgumentException("Installer redirects must target an approved Doppler URL.")
        }
    return URI(trusted).toURL()
}

internal fun createInstallerTempFile(): Path = Files.createTempFile(INSTALLER_TEMP_PREFIX, INSTALLER_TEMP_SUFFIX)

/** Deletes only files matching the exact boundary used by [createInstallerTempFile]. */
internal fun deleteInstallerTempFile(path: Path): Boolean {
    val normalized = path.toAbsolutePath().normalize()
    val tempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    val fileName = normalized.fileName?.toString().orEmpty()
    require(
        normalized.parent == tempDirectory &&
            fileName.startsWith(INSTALLER_TEMP_PREFIX) &&
            fileName.endsWith(INSTALLER_TEMP_SUFFIX),
    ) { "Refusing to delete a file outside the Doppler installer temporary-file boundary." }
    return Files.deleteIfExists(normalized)
}

internal fun installerSha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Where the reviewed installer places the Doppler binary. */
internal enum class InstallerScope {
    /** Runs without sudo and installs into `$HOME/.local/bin` via the official `--install-path` option. */
    USER,

    /** Runs with sudo and lets the official script choose a system bin directory. */
    SYSTEM,
}

/** Maps the reviewed-installer dialog button index to a scope; any other index means the user cancelled. */
internal fun installerScopeForChoice(choice: Int): InstallerScope? =
    when (choice) {
        0 -> InstallerScope.USER
        1 -> InstallerScope.SYSTEM
        else -> null
    }

/** Validates a downloaded installer and stores it as an owner-read-only temporary file for review. */
internal fun saveReviewedInstaller(
    bytes: ByteArray,
    maxBytes: Int = MAX_INSTALLER_BYTES,
): ReviewedInstaller {
    require(bytes.isNotEmpty()) { "Downloaded installer is empty." }
    require(bytes.size <= maxBytes) { "Downloaded installer is too large." }
    requireShellScript(bytes)
    val sha256 = installerSha256(bytes)
    val path = createInstallerTempFile()
    try {
        Files.write(path, bytes)
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
        return ReviewedInstaller(path, sha256)
    } catch (error: Throwable) {
        deleteInstallerTempFile(path)
        throw error
    }
}

/** Rejects downloads that are not a shell script, such as an HTML error page. */
internal fun requireShellScript(bytes: ByteArray) {
    require(bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()) {
        "Downloaded installer is not a shell script."
    }
}

internal fun reviewedInstallerCommand(
    installer: ReviewedInstaller,
    scope: InstallerScope = InstallerScope.USER,
): String {
    val dollar = '$'
    val runInstaller =
        when (scope) {
            InstallerScope.USER -> {
                """
                install_dir="${dollar}HOME/.local/bin"
                mkdir -p -- "${dollar}install_dir"
                sh "${dollar}root_copy" --install-path "${dollar}install_dir"
                """.trimIndent()
            }

            InstallerScope.SYSTEM -> {
                "sh \"${dollar}root_copy\""
            }
        }
    val verifyingScript =
        """
        set -eu
        source_path=${dollar}1
        expected_sha256=${dollar}2
        umask 077
        root_copy=$dollar(mktemp /tmp/doppler-installer.XXXXXX)
        trap 'rm -f -- "${dollar}root_copy"' 0
        cat -- "${dollar}source_path" > "${dollar}root_copy"
        case "${dollar}expected_sha256" in
            *[!0-9a-f]*) printf '%s\n' 'Invalid installer checksum; refusing to run installer.' >&2; exit 1 ;;
        esac
        if [ "$dollar{#expected_sha256}" -ne 64 ]; then
            printf '%s\n' 'Invalid installer checksum; refusing to run installer.' >&2
            exit 1
        fi
        if command -v sha256sum >/dev/null 2>&1; then
            actual_sha256=$dollar(sha256sum "${dollar}root_copy" | awk '{print ${dollar}1}')
        elif command -v shasum >/dev/null 2>&1; then
            actual_sha256=$dollar(shasum -a 256 "${dollar}root_copy" | awk '{print ${dollar}1}')
        elif command -v openssl >/dev/null 2>&1; then
            actual_sha256=$dollar(openssl dgst -sha256 "${dollar}root_copy" | awk '{print ${dollar}NF}')
        else
            printf '%s\n' 'No SHA-256 utility found; refusing to run installer.' >&2
            exit 1
        fi
        if [ "${dollar}actual_sha256" != "${dollar}expected_sha256" ]; then
            printf '%s\n' 'Installer changed after review (SHA-256 mismatch); refusing to run installer.' >&2
            exit 1
        fi
        """.trimIndent() + "\n" + runInstaller
    require(SHA256_HEX.matches(installer.sha256)) { "Installer checksum must be a lowercase SHA-256 hex digest." }
    val sourcePath = shellQuote(installer.path.toString())
    val expectedSha256 = shellQuote(installer.sha256)
    val launcher = if (scope == InstallerScope.SYSTEM) "sudo sh" else "sh"
    return "$launcher -c ${shellQuote(verifyingScript)} sh $sourcePath $expectedSha256; " +
        "result=\$?; rm -f -- $sourcePath; exit \"\$result\""
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
