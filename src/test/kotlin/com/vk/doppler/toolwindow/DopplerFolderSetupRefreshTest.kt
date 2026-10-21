package com.vk.doppler.toolwindow

import com.intellij.openapi.fileEditor.FileEditorManager
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
import com.vk.doppler.editor.DopplerSecretsFile
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport
import com.vk.doppler.settings.DopplerFolderSetupListener
import com.vk.doppler.settings.DopplerFolderSetupState
import com.vk.doppler.settings.DopplerSettings
import org.junit.Assume.assumeFalse
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

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

    fun `test ready refresh preloads only the folder setup project`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("multiple-projects") { cli ->
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                awaitConfigLoads(panel)

                val nodes = projectNodes(panel).associateBy { it.dopplerProject.slug }
                val configured = requireNotNull(nodes["example"])
                assertTrue(configured.loaded)
                assertFalse(requireNotNull(nodes["other"]).loaded)
                val environment = configured.getChildAt(0) as EnvironmentNode
                assertEquals("development", environment.environment)
                assertEquals("dev", (environment.getChildAt(0) as ConfigNode).config.name)
                assertEquals(1, cli.callCount("configs"))
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun `test tree config click reuses validated account and checks it after loading secrets`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") { cli ->
            val panel = DopplerToolWindowPanel(project)
            var opened: DopplerSecretsFile? = null
            try {
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                val expectedAuthContext = requireNotNull(field("authContextId").get(panel) as? String)
                val projectNode = projectNodes(panel).single()
                val environmentNode = projectNode.getChildAt(0) as EnvironmentNode
                val configNode = environmentNode.getChildAt(0) as ConfigNode
                cli.clearCalls()

                clickConfig(panel, configNode)
                opened = awaitOpenConfig(configNode.projectSlug, configNode.config.name)

                assertEquals(listOf("secrets", "me"), cli.calls())
                assertEquals(expectedAuthContext, opened.authContextId)
                assertEquals("development", opened.environment)
                assertEquals(mapOf("OPENED" to "synthetic-value"), opened.remoteSnapshot)
                assertTrue(DopplerSecretsEditorService.getInstance(project).owns(opened))
            } finally {
                Disposer.dispose(panel)
                DopplerSecretsEditorService.invalidateCachedFiles(null)
                opened?.let { assertFalse(FileEditorManager.getInstance(project).isFileOpen(it)) }
            }
        }
    }

    fun `test focus always checks the Doppler account while manual refresh reloads the tree`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") { cli ->
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                cli.clearCalls()

                panel.refresh(CliOperation.TREE_PANEL_FOCUS)
                awaitRefresh(panel)
                assertEquals(1, cli.callCount("me"))
                assertEquals(0, cli.callCount("projects"))
                assertEquals(0, cli.callCount("configure"))
                assertEquals(0, cli.callCount("configs"))

                cli.clearCalls()
                panel.refresh(CliOperation.TREE_REFRESH_BUTTON)
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                assertEquals(1, cli.callCount("me"))
                assertEquals(1, cli.callCount("projects"))
                assertEquals(1, cli.callCount("configure"))
                assertEquals(1, cli.callCount("configs"))
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun `test changed Doppler account forces a full tree reload`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") { cli ->
            val previousContext = DopplerCli(cli.executable.toString()).accountInfo().authContextId
            val cached = DopplerFileSystem.getInstance().obtain("cached", "dev", previousContext)
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                cli.clearCalls()
                cli.setMode("other-account")

                panel.refresh(CliOperation.TREE_PANEL_FOCUS)
                awaitRefresh(panel)
                awaitConfigLoads(panel)

                assertEquals(1, cli.callCount("me"))
                assertEquals(1, cli.callCount("projects"))
                assertEquals(1, cli.callCount("configure"))
                assertEquals(1, cli.callCount("configs"))
                assertEquals(listOf("replacement"), projectSlugs(panel))
                assertEquals(DopplerFolderSetup("replacement", "prod"), folderSetupState().current)
                assertNull(DopplerFileSystem.getInstance().findFileByPath(cached.path))
            } finally {
                Disposer.dispose(panel)
                DopplerSecretsEditorService.invalidateCachedFiles(null)
            }
        }
    }

    fun `test auth-only refresh keeps config preloading in flight`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("empty-setup") { cli ->
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                val configured = projectNodes(panel).single()
                assertFalse(configured.loaded)
                cli.clearCalls()
                cli.setMode("blocked-configs")

                val loadFailure = AtomicReference<Throwable>()
                val loadThread = Thread(
                    { loadConfigs(panel, configured) },
                    "Doppler config preload test",
                ).apply {
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> loadFailure.set(error) }
                }
                loadThread.start()
                awaitCall(cli, "configs")
                assertTrue(configured.loading)

                // refresh() advances the project-refresh generation immediately. Releasing the
                // config request afterwards proves its callback is governed independently.
                panel.refresh(CliOperation.TREE_PANEL_FOCUS)
                cli.releaseConfigs()
                awaitConfigLoads(panel)
                awaitRefresh(panel)
                loadThread.join(TimeUnit.SECONDS.toMillis(5))
                assertFalse("Config preload scheduling should return", loadThread.isAlive)
                loadFailure.get()?.let { throw AssertionError("Could not start config preload", it) }

                assertTrue(configured.loaded)
                assertEquals("dev", ((configured.getChildAt(0) as EnvironmentNode).getChildAt(0) as ConfigNode).config.name)
                assertEquals(1, cli.callCount("me"))
                assertEquals(0, cli.callCount("projects"))
                assertEquals(0, cli.callCount("configure"))
                assertEquals(1, cli.callCount("configs"))
            } finally {
                cli.releaseConfigs()
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

    private fun awaitConfigLoads(panel: DopplerToolWindowPanel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (projectNodes(panel).any { it.loading } && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertFalse("Config preloading should finish", projectNodes(panel).any { it.loading })
    }

    private fun awaitCall(cli: FakeCli, command: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (cli.callCount(command) == 0 && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Expected Doppler CLI call: $command", cli.callCount(command) > 0)
    }

    private fun awaitOpenConfig(projectSlug: String, configName: String): DopplerSecretsFile {
        val manager = FileEditorManager.getInstance(project)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var opened: DopplerSecretsFile? = null
        while (opened == null && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            opened = manager.openFiles.filterIsInstance<DopplerSecretsFile>()
                .firstOrNull { it.projectSlug == projectSlug && it.configName == configName }
            if (opened == null) Thread.sleep(10)
        }
        assertNotNull("Config click should open its Doppler editor", opened)
        return requireNotNull(opened)
    }

    private fun clickConfig(panel: DopplerToolWindowPanel, node: ConfigNode) {
        val tree = field("tree").get(panel) as JTree
        val environment = node.parent as EnvironmentNode
        val projectNode = environment.parent as ProjectNode
        tree.setSize(600, 600)
        tree.expandPath(TreePath(projectNode.path))
        tree.expandPath(TreePath(environment.path))
        tree.doLayout()
        val bounds = requireNotNull(tree.getPathBounds(TreePath(node.path)))
        val event = MouseEvent(
            tree,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            bounds.x + bounds.width / 2,
            bounds.y + bounds.height / 2,
            1,
            false,
            MouseEvent.BUTTON1,
        )
        tree.mouseListeners.filterIsInstance<DopplerTreeClickListener>().single().mouseClicked(event)
    }

    private fun isRefreshing(panel: DopplerToolWindowPanel): Boolean =
        field("refreshing").getBoolean(panel)

    private fun loadConfigs(panel: DopplerToolWindowPanel, node: ProjectNode) {
        DopplerToolWindowPanel::class.java.getDeclaredMethod(
            "loadConfigs",
            ProjectNode::class.java,
            Boolean::class.javaPrimitiveType,
            CliOperation::class.java,
        ).apply { isAccessible = true }.invoke(panel, node, false, CliOperation.TREE_EXPAND)
    }

    private fun applyRefreshResult(panel: DopplerToolWindowPanel, generation: Int, projects: List<DopplerProject>) {
        val resultClass = DopplerToolWindowPanel::class.java.declaredClasses.single { it.simpleName == "RefreshResult" }
        val result = resultClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }.newInstance(
            DopplerStatusReport(DopplerStatus.READY, null, "replacement-context"),
            projects,
            DopplerFolderSetup("replacement", "dev"),
            false,
        )
        DopplerToolWindowPanel::class.java.getDeclaredMethod(
            "applyRefreshResult",
            resultClass,
            CliOperation::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(panel, result, CliOperation.TREE_REFRESH_BUTTON, generation)
    }

    private fun projectSlugs(panel: DopplerToolWindowPanel): List<String> {
        return projectNodes(panel).map { it.dopplerProject.slug }
    }

    private fun projectNodes(panel: DopplerToolWindowPanel): List<ProjectNode> {
        val root = field("rootNode").get(panel) as DefaultMutableTreeNode
        return root.children().toList().filterIsInstance<ProjectNode>()
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
        private val callsFile = directory.resolve("calls")
        private val releaseConfigsFile = directory.resolve("release-configs")

        init {
            Files.writeString(modeFile, initialMode)
            Files.writeString(callsFile, "")
            Files.writeString(executable, """
                #!/bin/sh
                mode="${'$'}(cat '$modeFile')"
                printf '%s\n' "${'$'}1" >> '$callsFile'
                case "${'$'}1" in
                  me)
                    if [ "${'$'}mode" = not-authenticated ]; then
                      printf '%s' '{"error":"You must provide a token. Run doppler login."}'
                      exit 1
                    fi
                    if [ "${'$'}mode" = other-account ]; then
                      printf '%s' '{"slug":"other","type":"personal","token_preview":"replacement","workplace":{"slug":"other"}}'
                    else
                      printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}'
                    fi
                    ;;
                  projects)
                    if [ "${'$'}mode" = project-failure ]; then
                      printf '%s' '{"error":"Temporary project read failure"}'
                      exit 1
                    fi
                    if [ "${'$'}mode" = other-account ]; then
                      printf '%s' '[{"slug":"replacement","name":"Replacement"}]'
                    elif [ "${'$'}mode" = multiple-projects ]; then
                      printf '%s' '[{"slug":"example","name":"Example"},{"slug":"other","name":"Other"}]'
                    else
                      printf '%s' '[{"slug":"example","name":"Example"}]'
                    fi
                    ;;
                  configs)
                    if [ "${'$'}mode" = blocked-configs ]; then
                      attempts=0
                      while [ ! -f '$releaseConfigsFile' ] && [ "${'$'}attempts" -lt 200 ]; do
                        attempts=${'$'}((attempts + 1))
                        sleep 0.01
                      done
                    fi
                    if [ "${'$'}mode" = other-account ]; then
                      printf '%s' '[{"name":"prod","environment":"production"}]'
                    else
                      printf '%s' '[{"name":"dev","environment":"development"}]'
                    fi
                    ;;
                  secrets)
                    printf '%s' '{"OPENED":"synthetic-value"}'
                    ;;
                  configure)
                    if [ "${'$'}mode" = setup-failure ]; then
                      printf '%s' '{"error":"Temporary configuration read failure"}'
                      exit 1
                    fi
                    if [ "${'$'}mode" = empty-setup ]; then
                      printf '{}'
                    elif [ "${'$'}mode" = other-account ]; then
                      printf '%s' '{"project":"replacement","config":"prod"}'
                    else
                      printf '%s' '{"project":"example","config":"dev"}'
                    fi
                    ;;
                  *) exit 1 ;;
                esac
            """.trimIndent())
            check(executable.toFile().setExecutable(true))
        }

        fun clearCalls() = Files.writeString(callsFile, "")

        fun calls(): List<String> = Files.readAllLines(callsFile)

        fun callCount(command: String): Int = Files.readAllLines(callsFile).count { it == command }

        fun setMode(mode: String) = Files.writeString(modeFile, mode)

        fun releaseConfigs() = Files.writeString(releaseConfigsFile, "")

        override fun close() {
            releaseConfigs()
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
