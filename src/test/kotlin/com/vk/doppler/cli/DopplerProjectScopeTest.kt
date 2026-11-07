package com.vk.doppler.cli

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.completion.DopplerServiceNamesApi
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.SecretChanges
import com.vk.doppler.settings.DopplerSettings
import org.junit.Assume.assumeFalse
import java.nio.file.Files

class DopplerProjectScopeTest : BasePlatformTestCase() {
    fun `test project login works across browsing editing and folder setup when global login fails`() {
        assumeFalse(SystemInfo.isWindows)
        val folder = requireNotNull(project.basePath)
        val executable = Files.createTempFile("doppler-scoped-", ".sh")
        Disposer.register(testRootDisposable) { Files.deleteIfExists(executable) }
        Files.writeString(
            executable,
            """
            #!/bin/sh
            command=${'$'}1
            action=${'$'}2
            scope=
            scopes=0
            while [ "${'$'}#" -gt 0 ]; do
              case "${'$'}1" in
                --scope) scope=${'$'}2; scopes=${'$'}((scopes + 1)); shift ;;
                --) break ;;
              esac
              shift
            done
            if [ "${'$'}scope" != ${shellQuote(folder)} ] || [ "${'$'}scopes" != 1 ]; then
              printf '%s' '{"error":"You must provide a token"}'
              exit 1
            fi
            case "${'$'}command" in
              me) printf '%s' '{"slug":"local","type":"cli","token_preview":"synthetic","workplace":{"slug":"team"}}' ;;
              projects) printf '%s' '[{"slug":"example","name":"Example"}]' ;;
              configs) printf '%s' '[{"name":"dev","environment":"dev"}]' ;;
              --version) printf 'v3.77.1' ;;
              configure) if [ "${'$'}action" = get ]; then printf '{}'; fi ;;
              secrets) if [ "${'$'}action" = set ]; then cat >/dev/null; else printf '{}'; fi ;;
              *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        try {
            settings.cliPath = executable.toString()
            assertEquals(DopplerStatus.NOT_AUTHENTICATED, DopplerService.getInstance().checkStatus(false).status)
            val service = DopplerService.getInstance(project)
            val report = service.checkStatus()
            assertEquals(DopplerStatus.READY, report.status)
            val account = requireNotNull(report.authContextId)
            assertEquals("v3.77.1", report.detail)
            assertEquals(account, service.currentAuthContextId())
            val namesApi = DopplerServiceNamesApi(project)
            assertEquals(account, namesApi.currentAuthContextId())
            assertEquals("example", namesApi.listProjects(account).single().slug)
            assertEquals("dev", namesApi.listConfigs(account, "example").single().name)
            assertEmpty(namesApi.listSecretNames(account, "example", "dev", "dev"))
            assertEquals("example", service.listTreeProjects().single().slug)
            assertEquals("example", service.listProjects(account).single().slug)
            assertEquals("dev", service.listConfigs("example").single().name)
            assertEquals("dev", service.listConfigs(account, "example").single().name)
            assertEmpty(service.listSecretNames(account, "example", "dev"))
            assertEmpty(service.loadSecrets("example", "dev", account).secrets)
            assertEmpty(service.loadSecretsAfterTreeValidation("example", "dev", account).secrets)
            for (name in listOf("A", "--scope")) {
                service.applyChanges(account, "example", "dev", emptyMap(), SecretChanges(mapOf(name to "synthetic"), emptySet()))
                service.applyChanges(account, "example", "dev", emptyMap(), SecretChanges(emptyMap(), setOf(name)))
            }
            assertEquals(account, service.loadFolderSetup(folder).authContextId)
            assertEquals("dev", service.folderSetupConfigs(account, "example").single().name)
            service.setFolderSetup(folder, "example", "dev", account)
            service.clearFolderSetup(folder, account)
            assertEquals(account, service.injectionAccount(folder))
            assertTrue(service.downloadEnvironment(folder, "example", "dev").isEmpty())
        } finally {
            settings.cliPath = originalPath
        }
    }

    fun `test explicit folder scope takes precedence over the default scope`() {
        assumeFalse(SystemInfo.isWindows)
        val executable = Files.createTempFile("doppler-folder-scope-", ".sh")
        Disposer.register(testRootDisposable) { Files.deleteIfExists(executable) }
        Files.writeString(
            executable,
            """
            #!/bin/sh
            if [ "${'$'}*" != 'configure get project config --scope /explicit/folder --json --no-check-version' ]; then
              printf '%s' '{"error":"Unexpected scope"}'
              exit 1
            fi
            printf '{}'
            """.trimIndent(),
        )
        assertTrue(executable.toFile().setExecutable(true))
        assertEquals(DopplerFolderSetup(), DopplerCli(executable.toString(), scope = "/default").folderSetup("/explicit/folder"))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
