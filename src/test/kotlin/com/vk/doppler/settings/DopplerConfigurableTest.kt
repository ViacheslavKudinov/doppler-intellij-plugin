package com.vk.doppler.settings

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JTextField

class DopplerConfigurableTest : BasePlatformTestCase() {
    fun `test global settings are discoverable without a project and folder setup requires a project`() {
        val globalId = DopplerConfigurable::class.java.name
        val folderId = DopplerFolderSetupConfigurable::class.java.name
        val injectionId = DopplerInjectionConfigurable::class.java.name

        fun ids(context: com.intellij.openapi.project.Project?) =
            com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
                .getConfigurables(context, true)
                .filterIsInstance<com.intellij.openapi.options.SearchableConfigurable>()
                .map { it.id }

        val withoutProject = ids(null)
        assertTrue(withoutProject.contains(globalId))
        assertFalse(withoutProject.contains(folderId))
        assertFalse(withoutProject.contains(injectionId))
        val withProject = ids(project)
        assertTrue(withProject.contains(globalId))
        assertTrue(withProject.contains(folderId))
        assertTrue(withProject.contains(injectionId))
        val defaults =
            ids(
                com.intellij.openapi.project.ProjectManager
                    .getInstance()
                    .defaultProject,
            )
        assertTrue(defaults.contains(globalId))
        assertFalse(defaults.contains(folderId))
        assertFalse(defaults.contains(injectionId))

        val configurable = DopplerConfigurable()
        try {
            val panel = configurable.createPanel()
            assertNotNull(UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java))
            assertNull(UIUtil.findComponentOfType(panel, DopplerFolderSetupPanel::class.java))
            val links =
                UIUtil
                    .uiTraverser(panel)
                    .filter(com.intellij.ui.components.ActionLink::class.java)
                    .map { it.text }
                    .toList()
            assertContainsElements(links, "Folder Setup…", "Run and Debug…")
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test project settings links explain that an IDE project is required outside a settings dialog`() {
        val messages = CopyOnWriteArrayList<String>()
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                messages += message
                Messages.OK
            },
            testRootDisposable,
        )
        val configurable = DopplerConfigurable()
        try {
            val panel = configurable.createPanel()
            val links = UIUtil.uiTraverser(panel).filter(ActionLink::class.java).toList()

            links.single { it.text == "Folder Setup…" }.doClick()
            links.single { it.text == "Run and Debug…" }.doClick()

            assertEquals(
                listOf(
                    "Open an IDE project to configure its Doppler folder setup.",
                    "Open an IDE project to configure its Doppler Run and Debug injection.",
                ),
                messages,
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test refresh interval and CLI logging apply and reset through the settings panel`() {
        val settings = DopplerSettings.getInstance()
        val originalState = settings.state.copy()
        val configurable = DopplerConfigurable()
        try {
            settings.cliPath = "/unique/doppler/path"
            settings.refreshIntervalSeconds = 17
            settings.logCliCalls = false
            val panel = configurable.createPanel()
            panel.reset()
            val interval =
                UIUtil
                    .uiTraverser(panel)
                    .filter(JTextField::class.java)
                    .single { it.text == "17" }
            val logging =
                UIUtil
                    .uiTraverser(panel)
                    .filter(JCheckBox::class.java)
                    .single { it.text == "Log CLI calls" }

            interval.text = "42"
            logging.isSelected = true
            assertTrue(panel.isModified())
            panel.apply()
            assertEquals(42, settings.refreshIntervalSeconds)
            assertTrue(settings.logCliCalls)

            interval.text = "120"
            logging.isSelected = false
            panel.reset()
            assertEquals("42", interval.text)
            assertTrue(logging.isSelected)
            assertFalse(panel.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.loadState(originalState)
        }
    }

    fun `test connection button reports successful and failed CLI checks`() {
        assumeFalse(SystemInfo.isWindows)
        val executable = Files.createTempFile("doppler-settings-connection-", ".sh")
        val settings = DopplerSettings.getInstance()
        val originalState = settings.state.copy()
        val messages = CopyOnWriteArrayList<String>()
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                messages += message
                Messages.OK
            },
            testRootDisposable,
        )
        val configurable = DopplerConfigurable()
        try {
            writeExecutable(
                executable,
                """
                case "${'$'}1" in
                  me) printf '%s' '{"slug":"identity","type":"personal","token_preview":"synthetic","name":"Local User","workplace":{"slug":"team","name":"Example Team"}}' ;;
                  --version) printf 'test-version' ;;
                  *) exit 1 ;;
                esac
            """,
            )
            val panel = configurable.createPanel()
            panel.reset()
            val cliPath = requireNotNull(UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java))
            val testConnection =
                UIUtil
                    .uiTraverser(panel)
                    .filter(JButton::class.java)
                    .single { it.text == "Test Connection" }
            cliPath.text = executable.toString()

            testConnection.doClick()
            val success = awaitMessage(messages, 1)
            assertTrue(success.contains("Connected to Doppler."))
            assertTrue(success.contains("Identity: Local User (identity)"))
            assertTrue(success.contains("Workplace: Example Team (team)"))
            assertTrue(success.contains("CLI version: test-version"))
            assertEquals(executable.toString(), settings.cliPath)

            writeExecutable(executable, "printf 'synthetic failure' >&2; exit 1")
            testConnection.doClick()
            assertEquals("synthetic failure", awaitMessage(messages, 2))
        } finally {
            configurable.disposeUIResources()
            settings.loadState(originalState)
            Files.deleteIfExists(executable)
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
        Files.writeString(
            executable,
            """
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
            """.trimIndent(),
        )
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
            UIUtil
                .uiTraverser(setup)
                .filter(JButton::class.java)
                .first { it.text == "Reload" }
                .doClick()
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
            Files.writeString(
                replacement,
                """
                #!/bin/sh
                printf '%s\n' "${'$'}@" >> '$replacementCalls'
                exec '$executable' "${'$'}@"
                """.trimIndent(),
            )
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
            val setArguments =
                listOf(
                    "configure",
                    "set",
                    "--scope",
                    requireNotNull(project.basePath),
                    "--silent",
                    "--json",
                    "--no-check-version",
                    "--",
                    "project=example",
                    "config=stg",
                )
            val preflightArguments = listOf("me", "--json", "--no-check-version")
            assertEquals(
                preflightArguments + setArguments,
                Files.readAllLines(replacementCalls).take(preflightArguments.size + setArguments.size),
            )
            val arguments = Files.readAllLines(calls)
            assertTrue(arguments.containsAll(listOf("set", "--scope", project.basePath, "--", "project=example", "config=stg")))

            setup.clear.doClick()
            assertTrue(panel.isModified())
            panel.apply()
            awaitEnabled(setup)
            assertEquals("parent", setup.projectChoice.selectedItem)
            assertEquals("prod", setup.configChoice.selectedItem)
            assertEquals("prod", DopplerFolderSetupState.getInstance(project).current.configName)
            assertEquals(
                listOf(
                    "configure",
                    "unset",
                    "--scope",
                    requireNotNull(project.basePath),
                    "--silent",
                    "--json",
                    "--no-check-version",
                    "--",
                    "project",
                    "config",
                ),
                Files.readAllLines(calls).drop(arguments.size),
            )
            assertFalse(panel.isModified())

            // Simulate the same background read used by the 30-second timer.
            Files.writeString(state, """{"enclave.project":"example","enclave.config":"dev"}""")
            val cache = DopplerFolderSetupState.getInstance(project)
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .executeOnPooledThread {
                    cache.refreshFromCli()
                }.get()
            UIUtil.dispatchAllInvocationEvents()
            awaitEnabled(setup)
            assertEquals("example", cache.current.projectSlug)
            assertEquals("dev", setup.configChoice.selectedItem)

            setup.configChoice.selectedItem = "stg"
            Files.writeString(state, """{"enclave.project":"parent","enclave.config":"prod"}""")
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .executeOnPooledThread {
                    cache.refreshFromCli()
                }.get()
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("parent", cache.current.projectSlug)
            assertEquals("stg", setup.configChoice.selectedItem)
            assertTrue(panel.isModified())

            Files.writeString(state, "{}")
            UIUtil
                .uiTraverser(setup)
                .filter(JButton::class.java)
                .first { it.text == "Reload" }
                .doClick()
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
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(15)
        while (!setup.projectChoice.isEnabled && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Folder setup should finish loading", setup.projectChoice.isEnabled)
    }

    private fun awaitMessage(
        messages: List<String>,
        count: Int,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (messages.size < count && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertEquals("Expected a settings dialog message", count, messages.size)
        return messages[count - 1]
    }

    private fun writeExecutable(
        path: java.nio.file.Path,
        body: String,
    ) {
        Files.writeString(path, "#!/bin/sh\n${body.trimIndent()}\n")
        assertTrue(path.toFile().setExecutable(true))
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
