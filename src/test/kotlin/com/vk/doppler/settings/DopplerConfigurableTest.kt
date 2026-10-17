package com.vk.doppler.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import com.intellij.openapi.util.SystemInfo
import org.junit.Assume.assumeFalse
import java.nio.file.Files

class DopplerConfigurableTest : BasePlatformTestCase() {
    fun `test global settings are discoverable without a project and folder setup requires a project`() {
        val globalId = DopplerConfigurable::class.java.name
        val folderId = DopplerFolderSetupConfigurable::class.java.name
        fun ids(context: com.intellij.openapi.project.Project?) =
            com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil.getConfigurables(context, true)
                .filterIsInstance<com.intellij.openapi.options.SearchableConfigurable>().map { it.id }

        val withoutProject = ids(null)
        assertTrue(withoutProject.contains(globalId))
        assertFalse(withoutProject.contains(folderId))
        val withProject = ids(project)
        assertTrue(withProject.contains(globalId))
        assertTrue(withProject.contains(folderId))
        val defaults = ids(com.intellij.openapi.project.ProjectManager.getInstance().defaultProject)
        assertTrue(defaults.contains(globalId))
        assertFalse(defaults.contains(folderId))

        val configurable = DopplerConfigurable()
        try {
            val panel = configurable.createPanel()
            assertNotNull(UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java))
            assertNull(UIUtil.findComponentOfType(panel, DopplerFolderSetupPanel::class.java))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test folder setup loads stages applies resets and clears through settings`() {
        assumeFalse(SystemInfo.isWindows)
        val directory = Files.createTempDirectory("doppler-folder-settings-")
        val executable = directory.resolve("doppler")
        val state = directory.resolve("state.json")
        val calls = directory.resolve("calls")
        val failure = directory.resolve("fail")
        Files.writeString(state, """{"enclave.project":"example","enclave.config":"dev"}""")
        Files.writeString(executable, """
            #!/bin/sh
            case "${'$'}1" in
              me) printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}' ;;
              configure)
                case "${'$'}2" in
                  get) cat '$state' ;;
                  set)
                    if [ -f '$failure' ]; then printf '{"error":"Setup failed"}'; exit 1; fi
                    printf '%s\n' "${'$'}@" >> '$calls'
                    printf '%s' '{"enclave.project":"example","enclave.config":"stg"}' > '$state'
                    ;;
                  unset)
                    printf '%s\n' "${'$'}@" >> '$calls'
                    printf '%s' '{"enclave.project":"parent","enclave.config":"prod"}' > '$state'
                    ;;
                esac ;;
              projects) printf '%s' '[{"id":"example","name":"example","slug":"example"},{"slug":"parent"}]' ;;
              configs) printf '%s' '[{"name":"dev"},{"name":"stg"},{"name":"prod"}]' ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val configurable = DopplerConfigurable()
        val folderConfigurable = DopplerFolderSetupConfigurable(project)
        try {
            settings.cliPath = executable.toString()
            val globalPanel = configurable.createPanel()
            globalPanel.reset()
            val panel = folderConfigurable.createPanel()
            panel.reset()
            val setup = requireNotNull(UIUtil.findComponentOfType(panel, DopplerFolderSetupPanel::class.java))
            UIUtil.uiTraverser(setup).filter(JButton::class.java).first { it.text == "Reload" }.doClick()
            awaitEnabled(setup)
            assertEquals("example", setup.projectChoice.selectedItem)
            assertEquals("dev", setup.configChoice.selectedItem)
            assertFalse(panel.isModified())

            setup.configChoice.selectedItem = null
            try {
                panel.apply()
                fail("Incomplete setup must not be saved")
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                assertTrue(setup.status.text.startsWith("Choose both"))
                assertNotNull(setup.status.icon)
                assertEquals("error", setup.configChoice.getClientProperty("JComponent.outline"))
                assertTrue(setup.validationMessageTimer.isRunning)
                assertEquals(5_000, setup.validationMessageTimer.initialDelay)
                assertFalse(Files.exists(calls))
            }
            // Fire the timer callback without a five-second wall-clock wait.
            setup.validationMessageTimer.actionListeners.forEach {
                it.actionPerformed(java.awt.event.ActionEvent(setup, 0, "timeout"))
            }
            assertFalse(setup.status.text.startsWith("Choose both"))
            assertNull(setup.status.icon)
            assertNull(setup.configChoice.getClientProperty("JComponent.outline"))
            assertFalse(setup.validationMessageTimer.isRunning)
            assertTrue(panel.isModified())
            try {
                panel.apply()
                fail("Incomplete setup must still be blocked after the message expires")
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                assertTrue(setup.validationMessageTimer.isRunning)
            }
            setup.configChoice.selectedItem = "stg"
            assertFalse(setup.status.text.startsWith("Choose both"))
            assertNull(setup.status.icon)
            assertNull(setup.configChoice.getClientProperty("JComponent.outline"))
            assertFalse(setup.validationMessageTimer.isRunning)
            assertTrue(panel.isModified())
            assertFalse(Files.exists(calls))
            panel.reset()
            awaitEnabled(setup)
            assertEquals("dev", setup.configChoice.selectedItem)
            assertFalse(panel.isModified())
            assertFalse(Files.exists(calls))

            setup.configChoice.selectedItem = "stg"
            Files.createFile(failure)
            try {
                panel.apply()
                fail("Expected a failed setup to keep the settings modified")
            } catch (_: com.intellij.openapi.options.ConfigurationException) {
                assertTrue(panel.isModified())
            }
            Files.delete(failure)
            val replacement = directory.resolve("replacement-doppler")
            val replacementCalls = directory.resolve("replacement-calls")
            Files.writeString(replacement, """
                #!/bin/sh
                printf '%s\n' "${'$'}@" >> '$replacementCalls'
                exec '$executable' "${'$'}@"
            """.trimIndent())
            assertTrue(replacement.toFile().setExecutable(true))
            val cliPath = requireNotNull(UIUtil.findComponentOfType(globalPanel, TextFieldWithBrowseButton::class.java))
            cliPath.text = replacement.toString()
            assertEquals(executable.toString(), settings.cliPath)
            assertEquals("stg", setup.configChoice.selectedItem)
            assertFalse(Files.exists(replacementCalls))
            globalPanel.apply()
            panel.apply()
            awaitEnabled(setup)
            assertEquals("stg", DopplerFolderSetupState.getInstance(project).current.configName)
            assertFalse(panel.isModified())
            assertEquals(replacement.toString(), settings.cliPath)
            val setArguments = listOf("configure", "set", "--scope", requireNotNull(project.basePath),
                "--silent", "--json", "--no-check-version", "--", "project=example", "config=stg")
            val preflightArguments = listOf("me", "--json", "--no-check-version")
            assertEquals(preflightArguments + setArguments,
                Files.readAllLines(replacementCalls).take(preflightArguments.size + setArguments.size))
            val arguments = Files.readAllLines(calls)
            assertTrue(arguments.containsAll(listOf("set", "--scope", project.basePath, "--", "project=example", "config=stg")))

            setup.clear.doClick()
            assertTrue(panel.isModified())
            panel.apply()
            awaitEnabled(setup)
            assertEquals("parent", setup.projectChoice.selectedItem)
            assertEquals("prod", setup.configChoice.selectedItem)
            assertEquals("prod", DopplerFolderSetupState.getInstance(project).current.configName)
            assertEquals(listOf("configure", "unset", "--scope", requireNotNull(project.basePath),
                "--silent", "--json", "--no-check-version", "--", "project", "config"),
                Files.readAllLines(calls).drop(arguments.size))
            assertFalse(panel.isModified())

            // Simulate the same background read used by the 30-second timer.
            Files.writeString(state, """{"enclave.project":"example","enclave.config":"dev"}""")
            val cache = DopplerFolderSetupState.getInstance(project)
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                cache.refreshFromCli()
            }.get()
            UIUtil.dispatchAllInvocationEvents()
            awaitEnabled(setup)
            assertEquals("example", cache.current.projectSlug)
            assertEquals("dev", setup.configChoice.selectedItem)

            setup.configChoice.selectedItem = "stg"
            Files.writeString(state, """{"enclave.project":"parent","enclave.config":"prod"}""")
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                cache.refreshFromCli()
            }.get()
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("parent", cache.current.projectSlug)
            assertEquals("stg", setup.configChoice.selectedItem)
            assertTrue(panel.isModified())

            Files.writeString(state, "{}")
            UIUtil.uiTraverser(setup).filter(JButton::class.java).first { it.text == "Reload" }.doClick()
            awaitEnabled(setup)
            assertNull(setup.projectChoice.selectedItem)
            assertNull(setup.configChoice.selectedItem)
            val savedCalls = Files.readAllLines(calls)
            repeat(2) {
                setup.clear.doClick()
                assertFalse("Clearing an empty setup must not mark Settings modified", panel.isModified())
                panel.apply()
                assertEquals(savedCalls, Files.readAllLines(calls))
            }
            setup.projectChoice.selectedItem = "example"
            awaitEnabled(setup)
            setup.configChoice.selectedItem = "dev"
            assertTrue(panel.isModified())
            setup.clear.doClick()
            assertFalse("Clearing unsaved choices must return to the empty baseline", panel.isModified())
            panel.apply()
            assertEquals(savedCalls, Files.readAllLines(calls))
        } finally {
            folderConfigurable.disposeUIResources()
            configurable.disposeUIResources()
            settings.cliPath = originalPath
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun awaitEnabled(setup: DopplerFolderSetupPanel) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (!setup.projectChoice.isEnabled && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Folder setup should finish loading", setup.projectChoice.isEnabled)
    }

    fun `test CLI browse field preserves manual entry apply reset and automatic detection`() {
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val configurable = DopplerConfigurable()
        try {
            settings.cliPath = "/usr/local/bin/doppler"
            val panel = configurable.createPanel()
            panel.reset()
            val field = requireNotNull(UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java))
            val browseButton = requireNotNull(UIUtil.findComponentOfType(field, JButton::class.java))
            assertTrue(browseButton.isEnabled)
            assertTrue(field.textField.isEditable)
            assertEquals(settings.cliPath, field.text)

            field.text = "/opt/homebrew/bin/doppler"
            assertTrue(panel.isModified())
            panel.apply()
            assertEquals("/opt/homebrew/bin/doppler", settings.cliPath)

            field.text = "/another/path"
            panel.reset()
            assertEquals("/opt/homebrew/bin/doppler", field.text)
            assertFalse(panel.isModified())

            field.text = ""
            panel.apply()
            assertEquals("", settings.cliPath)
        } finally {
            configurable.disposeUIResources()
            settings.cliPath = originalPath
        }
    }
}
