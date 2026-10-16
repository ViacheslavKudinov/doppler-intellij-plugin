package com.vk.doppler.cli

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import com.vk.doppler.settings.DopplerSettings
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.editor.DopplerFileSystem
import com.vk.doppler.editor.DopplerSecretsEditorService
import com.vk.doppler.editor.SecretsDocumentFormat

class DopplerCliExecutionTest : BasePlatformTestCase() {

    fun `test auto refresh makes only two account checks and one secrets read`() {
        checkAutoRefreshCalls("stable", listOf("me", "secrets", "me"), false)
    }

    fun `test auto refresh rejects account change before reading secrets`() {
        checkAutoRefreshCalls("before", listOf("me"), true)
    }

    fun `test auto refresh rejects account change during secrets read`() {
        checkAutoRefreshCalls("after", listOf("me", "secrets", "me"), true)
    }

    fun `test auto refresh reports generic failure without invalidating unrelated configs`() {
        checkAutoRefreshCalls("generic", listOf("me", "secrets"), true, false)
    }

    private fun checkAutoRefreshCalls(
        mode: String,
        expectedCalls: List<String>,
        invalidated: Boolean,
        otherInvalidated: Boolean = invalidated,
    ) {
        assumeFalse(SystemInfo.isWindows)
        val executable = Files.createTempFile("doppler-refresh-test-", ".sh")
        val calls = Files.createTempFile("doppler-refresh-calls-", ".txt")
        Disposer.register(testRootDisposable) {
            Files.deleteIfExists(executable)
            Files.deleteIfExists(calls)
        }
        Files.writeString(executable, """
            #!/bin/sh
            printf '%s\n' "${'$'}1" >> '$calls'
            case "${'$'}1" in
              me)
                account=test
                if [ '$mode' = before ] || { [ '$mode' = after ] && [ "${'$'}(wc -l < '$calls')" -ge 3 ]; }; then
                  account=other
                fi
                printf '{"slug":"%s","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}' "${'$'}account"
                ;;
                            secrets)
                                if [ '$mode' = generic ]; then
                                    printf '%s' '{"error":"Config unavailable"}'
                                    exit 1
                                fi
                                printf '{}'
                                ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        try {
            settings.cliPath = executable.toString()
            // Establish the expected identity independently of the refresh under test.
            val identity = cli("""printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}'""").authContextId()
            val file = DopplerFileSystem.getInstance().obtain("refresh-test", mode, identity)
            file.setServerContent(SecretsDocumentFormat.serialize(emptyMap()), emptyMap())
            val other = DopplerFileSystem.getInstance().obtain("refresh-test", "$mode-other", identity)
            assertEquals(!invalidated, DopplerSecretsEditorService.getInstance(project).refreshFromServer(file))
            assertEquals(expectedCalls, Files.readAllLines(calls))
            assertEquals(invalidated, file.isInvalidated)
            assertEquals(otherInvalidated, other.isInvalidated)
        } finally {
            settings.cliPath = originalPath
        }
    }

    fun `test tree reload checks account once and never requests version`() {
        assumeFalse(SystemInfo.isWindows)
        val executable = Files.createTempFile("doppler-tree-test-", ".sh")
        val calls = Files.createTempFile("doppler-tree-calls-", ".txt")
        Disposer.register(testRootDisposable) {
            Files.deleteIfExists(executable)
            Files.deleteIfExists(calls)
        }
        Files.writeString(executable, """
            #!/bin/sh
            printf '%s\n' "${'$'}1" >> '$calls'
            case "${'$'}1" in
              me) printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}' ;;
              projects|configs) printf '[]' ;;
              --version) printf 'test-version' ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        try {
            settings.cliPath = executable.toString()
            val service = DopplerService.getInstance()
            val report = service.checkStatus(includeVersion = false)
            assertEquals(DopplerStatus.READY, report.status)
            assertNotNull(report.authContextId)
            assertNull(report.detail)
            service.listTreeProjects()
            service.listConfigs("test-project")
            assertEquals(listOf("me", "projects", "configs"), Files.readAllLines(calls))
            val connection = service.checkStatus()
            assertEquals("test-version", connection.detail)
            assertEquals("test", connection.account?.slug)
            assertEquals("personal", connection.account?.type)
            assertEquals("test", connection.account?.workplaceSlug)
            assertEquals(listOf("me", "projects", "configs", "me", "--version"), Files.readAllLines(calls))
        } finally {
            settings.cliPath = originalPath
        }
    }

    fun `test CLI logging setting defaults off and survives state reload`() {
        val settings = DopplerSettings()
        assertFalse(settings.logCliCalls)
        settings.logCliCalls = true
        val restored = DopplerSettings()
        restored.loadState(settings.state.copy())
        assertTrue(restored.logCliCalls)
    }

    private fun cli(script: String): DopplerCli {
        val executable = Files.createTempFile("doppler-test-", ".sh")
        Disposer.register(testRootDisposable) { Files.deleteIfExists(executable) }
        Files.writeString(executable, "#!/bin/sh\n$script\n")
        assertTrue(executable.toFile().setExecutable(true))
        return DopplerCli(executable.toString())
    }

    fun `test account info exposes identity and workplace name without token preview`() {
        assumeFalse(SystemInfo.isWindows)
        val cli = cli("""printf '%s' '{"name":"VK-MacBook-Air-M4-13","slug":"alice","type":"personal","token_preview":"synthetic-preview","workplace":{"slug":"team","name":"Example Team"}}'""")
        val account = cli.accountInfo()
        assertEquals("alice", account.slug)
        assertEquals("VK-MacBook-Air-M4-13", account.name)
        assertEquals("Example Team", account.workplaceName)
        assertEquals(cli.authContextId(), account.authContextId)
        assertFalse(account.toString().contains("synthetic-preview"))
    }

    fun `test incomplete account information is rejected`() {
        assumeFalse(SystemInfo.isWindows)
        try {
            cli("""printf '%s' '{"slug":"alice","type":"personal","workplace":{"slug":"team"}}'""").accountInfo()
            fail("Expected invalid account response to fail")
        } catch (error: DopplerCliException) {
            assertEquals("The Doppler CLI returned incomplete account information.", error.message)
        }
    }

    fun `test auth context fingerprint ignores volatile me fields`() {
        val first = cli(
            "printf '%s' '{\"slug\":\"alice\",\"type\":\"personal\",\"token_preview\":\"dp.preview\"," +
                "\"workplace\":{\"slug\":\"team\"},\"created_at\":\"yesterday\",\"last_seen_at\":\"one\"}'",
        )
        val second = cli(
            "printf '%s' '{\"slug\":\"alice\",\"type\":\"personal\",\"token_preview\":\"dp.preview\"," +
                "\"workplace\":{\"slug\":\"team\"},\"created_at\":\"today\",\"last_seen_at\":\"two\"}'",
        )
        val otherAccount = cli(
            "printf '%s' '{\"slug\":\"bob\",\"type\":\"personal\",\"token_preview\":\"dp.other\"," +
                "\"workplace\":{\"slug\":\"team\"}}'",
        )

        assertEquals(first.authContextId(), second.authContextId())
        assertFalse(first.authContextId() == otherAccount.authContextId())
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
        val cli = cli("printf '%s\\n' \"\$@\" > '$marker'\nif [ \"\$2\" = set ]; then cat >/dev/null; fi\nexit 0")

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
