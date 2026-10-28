package com.vk.doppler.cli

import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Finds the `doppler` binary. The CLI is usually on PATH, but GUI-launched IDEs on macOS
 * frequently inherit a minimal PATH that misses Homebrew, so well-known install locations
 * are probed as a fallback.
 */
object DopplerExecutableLocator {
    private val EXECUTABLE_NAME: String
        get() = if (SystemInfo.isWindows) "doppler.exe" else "doppler"

    private fun findOnPath(): File? =
        System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.map { File(it, EXECUTABLE_NAME) }
            ?.firstOrNull { it.canExecute() }

    private fun fallbackDirectories(): List<String> =
        when {
            SystemInfo.isWindows -> {
                listOfNotNull(
                    System.getenv("ProgramFiles")?.let { "$it\\doppler" },
                    System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" },
                    System.getenv("USERPROFILE")?.let { "$it\\scoop\\shims" },
                )
            }

            else -> {
                listOfNotNull(
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                    "/usr/bin",
                    "/bin",
                    "/snap/bin",
                    System.getenv("HOME")?.let { "$it/bin" },
                    System.getenv("HOME")?.let { "$it/.local/bin" },
                )
            }
        }

    /** Returns an absolute path to the CLI, or `null` when it cannot be found. */
    fun find(explicitPath: String?): String? {
        if (!explicitPath.isNullOrBlank()) {
            val file = File(explicitPath)
            return if (file.canExecute()) file.absolutePath else null
        }

        findOnPath()?.let { return it.absolutePath }

        return fallbackDirectories()
            .asSequence()
            .map { File(it, EXECUTABLE_NAME) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }
}
