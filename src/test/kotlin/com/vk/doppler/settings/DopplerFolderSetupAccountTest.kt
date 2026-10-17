package com.vk.doppler.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.DopplerFolderSetup
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JButton

class DopplerFolderSetupAccountTest : BasePlatformTestCase() {
    fun `test set rejects switched account and requires explicit reload while preserving choices`() = withCli { fixture ->
        fixture.reload()
        fixture.setup.configChoice.selectedItem = "stg"
        fixture.account("beta")
        fixture.clearCalls()
        fixture.assertAccountChanged()
        assertEquals("example", fixture.setup.projectChoice.selectedItem)
        assertEquals("stg", fixture.setup.configChoice.selectedItem)
        assertTrue(fixture.panel.isModified())
        assertEquals(listOf("me"), fixture.calls())

        // A cache update must not replace either the edited choices or their account baseline.
        DopplerFolderSetupState.getInstance(project).update(DopplerFolderSetup("parent", "prod"))
        assertEquals("stg", fixture.setup.configChoice.selectedItem)
        fixture.account("alpha")
        fixture.clearCalls()
        fixture.assertAccountChanged()
        assertTrue(fixture.calls().isEmpty())

        fixture.reload()
        fixture.setup.configChoice.selectedItem = "stg"
        fixture.clearCalls()
        fixture.panel.apply()
        fixture.awaitIdle()
        assertEquals(listOf("me", "configure set"), fixture.calls().take(2))
        assertEquals(1, fixture.calls().count { it == "configure set" })
        assertFalse(fixture.panel.isModified())
    }

    fun `test clear rejects switched account and preserves pending clear`() = withCli { fixture ->
        fixture.reload()
        fixture.setup.clear.doClick()
        fixture.account("beta")
        fixture.clearCalls()
        fixture.assertAccountChanged()
        assertNull(fixture.setup.projectChoice.selectedItem)
        assertNull(fixture.setup.configChoice.selectedItem)
        assertTrue(fixture.panel.isModified())
        assertEquals(listOf("me"), fixture.calls())

        fixture.reload()
        fixture.setup.clear.doClick()
        fixture.clearCalls()
        fixture.panel.apply()
        fixture.awaitIdle()
        assertEquals(listOf("me", "configure unset"), fixture.calls().take(2))
        assertEquals(1, fixture.calls().count { it == "configure unset" })
        assertFalse(fixture.panel.isModified())
    }

    fun `test logout blocks mutation without discarding selections`() = withCli { fixture ->
        fixture.reload()
        fixture.setup.configChoice.selectedItem = "stg"
        fixture.account("logged-out")
        fixture.clearCalls()
        try {
            fixture.panel.apply()
            fail("Logged-out CLI must not change folder setup")
        } catch (_: ConfigurationException) {
            assertEquals("stg", fixture.setup.configChoice.selectedItem)
            assertTrue(fixture.panel.isModified())
            assertEquals(listOf("me"), fixture.calls())
        }
    }

    fun `test initial load rejects an account switch while projects are being read`() = withCli { fixture ->
        Files.writeString(fixture.mode, "switch-projects")
        fixture.reload()
        assertFalse(fixture.setup.projectChoice.isEnabled)
        assertFalse(fixture.setup.configChoice.isEnabled)
        assertTrue(fixture.setup.status.text.contains("account changed"))
        assertNull(fixture.setup.projectChoice.selectedItem)
        assertFalse(fixture.panel.isModified())
        assertTrue(fixture.calls().none { it == "configure set" || it == "configure unset" })
    }

    fun `test config choices reject an account switch before requesting configs`() = withCli { fixture ->
        fixture.reload()
        fixture.account("beta")
        fixture.clearCalls()
        fixture.setup.projectChoice.selectedItem = "parent"
        fixture.awaitIdle()
        assertEquals(listOf("me"), fixture.calls())
        assertTrue(fixture.setup.status.text.contains("account changed"))
        assertNull(fixture.setup.configChoice.selectedItem)
    }

    fun `test config choices reject an account switch during their read`() = withCli { fixture ->
        fixture.reload()
        Files.writeString(fixture.mode, "switch-configs")
        fixture.setup.projectChoice.selectedItem = "parent"
        fixture.awaitIdle()
        assertFalse(fixture.setup.projectChoice.isEnabled)
        assertTrue(fixture.setup.status.text.contains("account changed"))
        assertNull(fixture.setup.configChoice.selectedItem)
        fixture.clearCalls()
        fixture.assertAccountChanged()
        assertTrue(fixture.calls().isEmpty())
    }

    private inner class Fixture(val directory: Path, val panel: DialogPanel, val setup: DopplerFolderSetupPanel) {
        val mode = directory.resolve("mode")
        private val reloadButton = UIUtil.uiTraverser(setup).filter(JButton::class.java).first { it.text == "Reload" }
        fun account(value: String) { Files.writeString(directory.resolve("account"), value) }
        fun calls(): List<String> = Files.readAllLines(directory.resolve("calls"))
        fun clearCalls() { Files.writeString(directory.resolve("calls"), "") }
        fun reload() { reloadButton.doClick(); awaitIdle() }
        fun awaitIdle() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!reloadButton.isEnabled && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertTrue("Folder setup task should finish", reloadButton.isEnabled)
        }
        fun assertAccountChanged() {
            try {
                panel.apply()
                fail("Account change must block Apply until Reload")
            } catch (expected: ConfigurationException) {
                assertTrue(expected.message!!.contains("account changed"))
                assertTrue(expected.message!!.contains("Reload"))
            }
        }
    }

    private fun withCli(test: (Fixture) -> Unit) {
        assumeFalse(SystemInfo.isWindows)
        val directory = Files.createTempDirectory("doppler-folder-account-")
        val executable = directory.resolve("doppler")
        val account = directory.resolve("account")
        val calls = directory.resolve("calls")
        val mode = directory.resolve("mode")
        val state = directory.resolve("state")
        Files.writeString(account, "alpha")
        Files.writeString(calls, "")
        Files.writeString(mode, "stable")
        Files.writeString(state, """{"project":"example","config":"dev"}""")
        Files.writeString(executable, """
            #!/bin/sh
            if [ "${'$'}1" = configure ]; then
              printf '%s %s\n' "${'$'}1" "${'$'}2" >> '$calls'
            else
              printf '%s\n' "${'$'}1" >> '$calls'
            fi
            case "${'$'}1" in
              me)
                identity=${'$'}(cat '$account')
                if [ "${'$'}identity" = logged-out ]; then
                  printf '%s' '{"error":"Run doppler login"}'
                  exit 1
                fi
                printf '{"slug":"%s","type":"personal","token_preview":"synthetic","workplace":{"slug":"team"}}' "${'$'}identity"
                ;;
              projects)
                if [ "${'$'}(cat '$mode')" = switch-projects ]; then printf beta > '$account'; fi
                printf '%s' '[{"slug":"example"},{"slug":"parent"}]'
                ;;
              configs)
                if [ "${'$'}(cat '$mode')" = switch-configs ]; then printf beta > '$account'; fi
                printf '%s' '[{"name":"dev"},{"name":"stg"}]'
                ;;
              configure)
                case "${'$'}2" in
                  get) cat '$state' ;;
                  set) printf '%s' '{"project":"example","config":"stg"}' > '$state' ;;
                  unset) printf '{}' > '$state' ;;
                esac
                ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val configurable = DopplerFolderSetupConfigurable(project)
        try {
            settings.cliPath = executable.toString()
            val panel = configurable.createPanel()
            panel.reset()
            val setup = requireNotNull(UIUtil.findComponentOfType(panel, DopplerFolderSetupPanel::class.java))
            test(Fixture(directory, panel, setup))
        } finally {
            configurable.disposeUIResources()
            settings.cliPath = originalPath
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
