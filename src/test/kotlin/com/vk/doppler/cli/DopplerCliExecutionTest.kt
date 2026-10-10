package com.vk.doppler.cli

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeFalse
import java.nio.file.Files

class DopplerCliExecutionTest : BasePlatformTestCase() {

    private fun cli(script: String): DopplerCli {
        val executable = Files.createTempFile("doppler-test-", ".sh")
        Disposer.register(testRootDisposable) { Files.deleteIfExists(executable) }
        Files.writeString(executable, "#!/bin/sh\n$script\n")
        assertTrue(executable.toFile().setExecutable(true))
        return DopplerCli(executable.toString())
    }

    fun `test empty read output is rejected instead of becoming an empty config`() {
        assumeFalse(SystemInfo.isWindows)
        val cli = cli("exit 0")
        try {
            cli.listSecrets("project", "config")
            fail("Expected DopplerCliException")
        } catch (expected: DopplerCliException) {
            assertEquals("The Doppler CLI returned no JSON output.", expected.message)
        }
    }

    fun `test a successful write may have no output`() {
        assumeFalse(SystemInfo.isWindows)
        val cli = cli("cat >/dev/null\nexit 0")
        cli.setSecret("project", "config", "A", "synthetic-value")
    }

    fun `test a successful deletion may have no output`() {
        assumeFalse(SystemInfo.isWindows)
        cli("exit 0").deleteSecrets("project", "config", listOf("A"))
    }

    fun `test names that look like flags are passed after the option terminator`() {
        assumeFalse(SystemInfo.isWindows)
        val marker = Files.createTempFile("doppler-args-", ".txt")
        Disposer.register(testRootDisposable) { Files.deleteIfExists(marker) }
        val cli = cli("printf '%s\\n' \"\$@\" > '$marker'\nexit 0")

        for (name in listOf("--config", "-h")) {
            cli.setSecret("project", "config", name, "synthetic-value")
            assertEquals(
                listOf("secrets", "set", "--project", "project", "--config", "config",
                    "--json", "--no-check-version", "--", name),
                Files.readAllLines(marker),
            )
            cli.deleteSecrets("project", "config", listOf(name))
            assertEquals(
                listOf("secrets", "delete", "--yes", "--project", "project", "--config", "config",
                    "--json", "--no-check-version", "--", name),
                Files.readAllLines(marker),
            )
        }

        for (name in listOf("A=B", "WITH SPACE", "")) {
            try {
                cli.setSecret("project", "config", name, "synthetic-value")
                fail("Expected DopplerCliException for set $name")
            } catch (_: DopplerCliException) {
            }
            try {
                cli.deleteSecrets("project", "config", listOf(name))
                fail("Expected DopplerCliException for delete $name")
            } catch (_: DopplerCliException) {
            }
        }
    }
}
