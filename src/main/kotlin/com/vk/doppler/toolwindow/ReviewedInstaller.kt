package com.vk.doppler.toolwindow

import java.nio.file.Path
import java.security.MessageDigest

internal data class ReviewedInstaller(val path: Path, val sha256: String)

internal fun installerSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun reviewedInstallerCommand(installer: ReviewedInstaller): String {
    val dollar = '$'
    val privilegedScript = """
        set -eu
        source_path=${dollar}1
        expected_sha256=${dollar}2
        umask 077
        root_copy=${dollar}(mktemp /tmp/doppler-installer.XXXXXX)
        trap 'rm -f -- "${dollar}root_copy"' EXIT
        cat -- "${dollar}source_path" > "${dollar}root_copy"
        if command -v sha256sum >/dev/null 2>&1; then
            printf '%s  %s\n' "${dollar}expected_sha256" "${dollar}root_copy" | sha256sum -c - >/dev/null
        elif command -v shasum >/dev/null 2>&1; then
            actual_sha256=${dollar}(shasum -a 256 "${dollar}root_copy" | awk '{print ${dollar}1}')
            test "${dollar}actual_sha256" = "${dollar}expected_sha256"
        elif command -v openssl >/dev/null 2>&1; then
            actual_sha256=${dollar}(openssl dgst -sha256 "${dollar}root_copy" | awk '{print ${dollar}NF}')
            test "${dollar}actual_sha256" = "${dollar}expected_sha256"
        else
            printf '%s\n' 'No SHA-256 utility found; refusing to run installer.' >&2
            exit 1
        fi
        sh "${dollar}root_copy"
    """.trimIndent()
    val sourcePath = shellQuote(installer.path.toString())
    val expectedSha256 = shellQuote(installer.sha256)
    return "sudo sh -c ${shellQuote(privilegedScript)} sh $sourcePath $expectedSha256; " +
        "result=\$?; rm -f -- $sourcePath; exit \"\$result\""
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"