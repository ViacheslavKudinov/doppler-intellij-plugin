package com.vk.doppler.toolwindow

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerCli
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.editor.DopplerFileSystem
import com.vk.doppler.editor.DopplerSecretsEditorService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport
import com.vk.doppler.settings.DopplerFolderSetupListener
import com.vk.doppler.settings.DopplerFolderSetupState
import com.vk.doppler.settings.DopplerSettings
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultMutableTreeNode

class DopplerFolderSetupRefreshTest : BasePlatformTestCase() {
    fun `test ready refresh loads projects and setup and invalidates stale cached files`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") { cli ->
            val fileSystem = DopplerFileSystem.getInstance()
            val currentContext = DopplerCli(cli.executable.toString()).accountInfo().authContextId
            val current = fileSystem.obtain("current", "dev", currentContext)
            val stale = fileSystem.obtain("stale", "dev", "old-context")

            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)

                assertEquals(DopplerFolderSetup("example", "dev"), folderSetupState().current)
                assertEquals(listOf("example"), projectSlugs(panel))
                assertSame(current, fileSystem.findFileByPath(current.path))
                assertNull(fileSystem.findFileByPath(stale.path))
                assertFalse(isRefreshing(panel))
            } finally {
                Disposer.dispose(panel)
                DopplerSecretsEditorService.invalidateCachedFiles(null)
            }
        }
    }

    fun `test non-ready refresh clears setup and displays the status message`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("not-authenticated") {
            folderSetupState().update(DopplerFolderSetup("example", "dev"))
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)

                assertEquals(DopplerFolderSetup(), folderSetupState().current)
                assertTrue(statusText(panel).contains("Not logged in to Doppler."))
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun `test folder setup failure preserves setup while project loading continues`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("setup-failure") {
            val configured = DopplerFolderSetup("existing", "prod")
            folderSetupState().update(configured)
            var notifications = 0
            project.messageBus.connect(testRootDisposable).subscribe(
                DopplerFolderSetupState.TOPIC, DopplerFolderSetupListener { notifications++ },
            )
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)

                assertEquals(configured, folderSetupState().current)
                assertEquals(listOf("example"), projectSlugs(panel))
                assertEquals("A failed read must not publish an empty setup", 0, notifications)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun `test project refresh failure invalidates caches and displays retryable error`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("project-failure") {
            val fileSystem = DopplerFileSystem.getInstance()
            val cached = fileSystem.obtain("cached", "dev", "cached-context")
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)

                assertNull(fileSystem.findFileByPath(cached.path))
                assertTrue(statusText(panel).contains("Could not reach Doppler: Temporary project read failure"))
                assertTrue(statusText(panel).contains("Retry"))
            } finally {
                Disposer.dispose(panel)
                DopplerSecretsEditorService.invalidateCachedFiles(null)
            }
        }
    }

    fun `test disposed refresh result is ignored`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") {
            val panel = DopplerToolWindowPanel(project)
            awaitRefresh(panel)
            assertEquals(listOf("example"), projectSlugs(panel))

            val generation = field("refreshGeneration").getInt(panel)
            Disposer.dispose(panel)
            applyRefreshResult(panel, generation, listOf(DopplerProject("replacement", "Replacement", null)))

            assertEquals("A disposed task must not replace the existing tree", listOf("example"), projectSlugs(panel))
        }
    }

    fun `test refresh action exposes its scope in tooltip and description`() {
        val action = DopplerTreeRefreshAction {}

        assertEquals("Refresh Doppler tree (does not reload open editors)", action.templatePresentation.text)
        assertEquals("Reload Doppler projects, configs, and folder setup", action.templatePresentation.description)
    }

    fun `test successful empty folder setup clears the bookmark`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("empty-setup") {
            val state = folderSetupState()
            state.update(DopplerFolderSetup("example", "dev"))
            var notifications = 0
            project.messageBus.connect(testRootDisposable).subscribe(
                DopplerFolderSetupState.TOPIC, DopplerFolderSetupListener { notifications++ },
            )
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)

                assertEquals(DopplerFolderSetup(), state.current)
                assertEquals("A successful empty read must remove the bookmark", 1, notifications)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    private fun withCli(initialMode: String, test: (FakeCli) -> Unit) {
        val cli = FakeCli(initialMode)
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val state = folderSetupState()
        val originalSetup = state.current
        try {
            settings.cliPath = cli.executable.toString()
            test(cli)
        } finally {
            settings.cliPath = originalPath
            state.update(originalSetup)
            cli.close()
        }
    }

    private fun awaitRefresh(panel: DopplerToolWindowPanel) {
        // Observe task completion without adding a test-only API to the tool window.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (isRefreshing(panel) && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertFalse("Tree refresh should finish", isRefreshing(panel))
    }

    private fun isRefreshing(panel: DopplerToolWindowPanel): Boolean =
        field("refreshing").getBoolean(panel)

    private fun applyRefreshResult(panel: DopplerToolWindowPanel, generation: Int, projects: List<DopplerProject>) {
        val resultClass = DopplerToolWindowPanel::class.java.declaredClasses.single { it.simpleName == "RefreshResult" }
        val result = resultClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }.newInstance(
            DopplerStatusReport(DopplerStatus.READY, null, "replacement-context"),
            projects,
            DopplerFolderSetup("replacement", "dev"),
        )
        DopplerToolWindowPanel::class.java.getDeclaredMethod(
            "applyRefreshResult",
            resultClass,
            CliOperation::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(panel, result, CliOperation.TREE_REFRESH_BUTTON, generation)
    }

    private fun projectSlugs(panel: DopplerToolWindowPanel): List<String> {
        val root = field("rootNode").get(panel) as DefaultMutableTreeNode
        return root.children().toList().filterIsInstance<ProjectNode>().map { it.dopplerProject.slug }
    }

    private fun statusText(panel: DopplerToolWindowPanel): String {
        val status = field("statusPanel").get(panel) as com.intellij.ui.components.JBPanelWithEmptyText
        return status.emptyText.wrappedFragmentsIterable
            .filterIsInstance<SimpleColoredComponent>()
            .joinToString("\n") { it.getCharSequence(false).toString() }
    }

    private fun field(name: String) = DopplerToolWindowPanel::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun folderSetupState() = DopplerFolderSetupState.getInstance(project)

    private class FakeCli(initialMode: String) : AutoCloseable {
        private val directory = Files.createTempDirectory("doppler-tree-refresh-")
        val executable: Path = directory.resolve("doppler")
        private val modeFile = directory.resolve("mode")

        init {
            Files.writeString(modeFile, initialMode)
            Files.writeString(executable, """
                #!/bin/sh
                mode="${'$'}(cat '$modeFile')"
                case "${'$'}1" in
                  me)
                    if [ "${'$'}mode" = not-authenticated ]; then
                      printf '%s' '{"error":"You must provide a token. Run doppler login."}'
                      exit 1
                    fi
                    printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}'
                    ;;
                  projects)
                    if [ "${'$'}mode" = project-failure ]; then
                      printf '%s' '{"error":"Temporary project read failure"}'
                      exit 1
                    fi
                    printf '%s' '[{"slug":"example","name":"Example"}]'
                    ;;
                  configure)
                    if [ "${'$'}mode" = setup-failure ]; then
                      printf '%s' '{"error":"Temporary configuration read failure"}'
                      exit 1
                    fi
                    if [ "${'$'}mode" = empty-setup ]; then printf '{}'; else printf '%s' '{"project":"example","config":"dev"}'; fi
                    ;;
                  *) exit 1 ;;
                esac
            """.trimIndent())
            check(executable.toFile().setExecutable(true))
        }

        override fun close() {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
