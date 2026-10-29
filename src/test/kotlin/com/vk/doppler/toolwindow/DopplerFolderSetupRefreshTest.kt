package com.vk.doppler.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.TestActionEvent
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
import com.vk.doppler.testutil.joinWithEventDispatch
import org.junit.Assume.assumeFalse
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class DopplerFolderSetupRefreshTest : BasePlatformTestCase() {
    fun `test missing CLI offers install settings and retry without making CLI calls`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("ready") { cli ->
            DopplerSettings.getInstance().cliPath = cli.executable.resolveSibling("missing-doppler").toString()
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                val text = statusText(panel)
                assertTrue(text.contains("The Doppler CLI was not found."))
                assertTrue(text.contains("Install in Terminal"))
                assertTrue(text.contains("Installation instructions"))
                assertTrue(text.contains("Set the CLI path"))
                assertTrue(text.contains("Retry"))
                assertTrue(cli.calls().isEmpty())
                assertNull(field("authContextId").get(panel))
                assertEquals(DopplerFolderSetup(), folderSetupState().current)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun `test status errors show detail and fallback without requesting projects`() =
        withPanel("status-error") { panel, cli ->
            assertTrue(statusText(panel).contains("Account service unavailable"))
            assertTrue(statusText(panel).contains("Retry"))
            assertEquals(listOf("me"), cli.calls())
            assertNull(field("authContextId").get(panel))
            invokePanel(panel, "showStatusFor", DopplerStatusReport(DopplerStatus.ERROR, null))
            assertTrue(statusText(panel).contains("Could not reach Doppler."))
        }

    fun `test empty project list shows retry and can recover on the next refresh`() =
        withPanel("empty-projects") { panel, cli ->
            assertTrue(projectNodes(panel).isEmpty())
            assertTrue(statusText(panel).contains("No Doppler projects found for this account."))
            assertTrue(statusText(panel).contains("Retry"))
            assertEquals(0, cli.callCount("configs"))

            cli.setMode("ready")
            panel.refresh(CliOperation.TREE_RETRY)
            awaitRefresh(panel)
            awaitConfigLoads(panel)
            assertEquals(listOf("example"), projectSlugs(panel))
            assertTrue(projectNodes(panel).single().loaded)
        }

    fun `test retry status link reloads projects after a failed startup`() =
        withPanel("project-failure") { panel, cli ->
            assertTrue(statusText(panel).contains("Temporary project read failure"))
            val status = field("statusPanel").get(panel) as com.intellij.ui.components.JBPanelWithEmptyText
            cli.setMode("ready")
            cli.clearCalls()

            clickStatusLink(panel, "Retry")
            awaitRefresh(panel)
            awaitConfigLoads(panel)

            assertEquals(listOf("example"), projectSlugs(panel))
            assertTrue(projectNodes(panel).single().loaded)
            assertEquals(DopplerFolderSetup("example", "dev"), folderSetupState().current)
            assertEquals(1, cli.callCount("me"))
            assertEquals(1, cli.callCount("projects"))
            assertFalse(status.isVisible)
        }

    fun `test authentication loss invalidates cached files and recovery reloads the tree`() =
        withPanel { panel, cli ->
            val context = requireNotNull(field("authContextId").get(panel) as? String)
            val fileSystem = DopplerFileSystem.getInstance()
            val cached = fileSystem.obtain("cached", "dev", context)
            cli.setMode("not-authenticated")
            cli.clearCalls()

            panel.refresh(CliOperation.TREE_WINDOW_FOCUS)
            awaitRefresh(panel)

            assertNull(field("authContextId").get(panel))
            assertNull(fileSystem.findFileByPath(cached.path))
            assertEquals(DopplerFolderSetup(), folderSetupState().current)
            assertTrue(statusText(panel).contains("Not logged in to Doppler."))
            assertEquals(listOf("me"), cli.calls())

            cli.setMode("ready")
            cli.clearCalls()
            panel.refresh(CliOperation.TREE_WINDOW_FOCUS)
            awaitRefresh(panel)
            awaitConfigLoads(panel)

            assertEquals(context, field("authContextId").get(panel))
            assertEquals(1, cli.callCount("projects"))
            assertEquals(1, cli.callCount("configure"))
            assertEquals(1, cli.callCount("configs"))
            assertEquals(DopplerFolderSetup("example", "dev"), folderSetupState().current)
        }

    fun `test project read failure keeps tree nodes but forces a full reload on focus`() =
        withPanel { panel, cli ->
            val node = projectNodes(panel).single()
            cli.setMode("project-failure")
            panel.refresh()
            awaitRefresh(panel)

            assertSame(node, projectNodes(panel).single())
            assertNull(field("authContextId").get(panel))
            assertTrue(statusText(panel).contains("Temporary project read failure"))
            assertFalse(node.loading)

            cli.setMode("ready")
            cli.clearCalls()
            panel.refresh(CliOperation.TREE_PANEL_FOCUS)
            awaitRefresh(panel)
            awaitConfigLoads(panel)

            assertSame(node, projectNodes(panel).single())
            assertTrue(node.loaded)
            assertEquals(1, cli.callCount("projects"))
            assertEquals(1, cli.callCount("configs"))
        }

    fun `test disposal removes both focus listeners and invalidates pending generations`() =
        withPanel { panel, _ ->
            val manager = field("focusManager").get(panel) as java.awt.KeyboardFocusManager
            val listener = field("focusListener").get(panel) as java.beans.PropertyChangeListener
            val refreshGeneration = field("refreshGeneration").getInt(panel)
            val configGeneration = field("configGeneration").getInt(panel)
            assertTrue(manager.getPropertyChangeListeners("focusOwner").contains(listener))
            assertTrue(manager.getPropertyChangeListeners("focusedWindow").contains(listener))

            Disposer.dispose(panel)

            assertFalse(manager.getPropertyChangeListeners("focusOwner").contains(listener))
            assertFalse(manager.getPropertyChangeListeners("focusedWindow").contains(listener))
            assertEquals(refreshGeneration + 1, field("refreshGeneration").getInt(panel))
            assertEquals(configGeneration + 1, field("configGeneration").getInt(panel))
        }

    fun `test refresh ignores duplicate requests and requests after disposal`() =
        withPanel { panel, cli ->
            cli.clearCalls()
            val generation = field("refreshGeneration").getInt(panel)
            field("refreshing").setBoolean(panel, true)
            try {
                panel.refresh()
                assertEquals(generation, field("refreshGeneration").getInt(panel))
                assertTrue(cli.calls().isEmpty())
            } finally {
                field("refreshing").setBoolean(panel, false)
            }
            Disposer.dispose(panel)
            val disposedGeneration = field("refreshGeneration").getInt(panel)
            panel.refresh()
            assertEquals(disposedGeneration, field("refreshGeneration").getInt(panel))
            assertTrue(cli.calls().isEmpty())
        }

    fun `test stale refresh results failures and completion cannot change current state`() =
        withPanel { panel, cli ->
            cli.clearCalls()
            val generation = field("refreshGeneration").getInt(panel)
            val setup = folderSetupState().current
            val context = field("authContextId").get(panel)
            applyRefreshResult(panel, generation - 1, listOf(DopplerProject("stale", "Stale", null)))
            invokePanel(panel, "handleRefreshFailure", IllegalStateException("Stale failure"), generation - 1)
            assertEquals(listOf("example"), projectSlugs(panel))
            assertEquals(setup, folderSetupState().current)
            assertEquals(context, field("authContextId").get(panel))
            assertFalse(statusText(panel).contains("Stale failure"))

            field("refreshing").setBoolean(panel, true)
            try {
                invokePanel(panel, "finishRefresh", generation - 1)
                assertTrue(isRefreshing(panel))
                invokePanel(panel, "finishRefresh", generation)
                assertFalse(isRefreshing(panel))
            } finally {
                field("refreshing").setBoolean(panel, false)
            }
            assertTrue(cli.calls().isEmpty())
        }

    fun `test expanding a failed project retries configs and loaded projects are not fetched twice`() =
        withPanel("empty-setup") { panel, cli ->
            val node = projectNodes(panel).single()
            val tree = field("tree").get(panel) as JTree
            val path = TreePath(node.path)
            cli.clearCalls()
            cli.setMode("config-failure")
            tree.expandPath(path)
            awaitConfigLoads(panel)
            assertTrue(node.configLoadFailed)
            assertFalse(node.loaded)
            assertTrue(node.getChildAt(0).toString().contains("Temporary config read failure"))

            tree.collapsePath(path)
            cli.setMode("ready")
            tree.expandPath(path)
            awaitConfigLoads(panel)
            assertTrue(node.loaded)
            assertFalse(node.configLoadFailed)
            assertEquals(2, cli.callCount("configs"))
            cli.clearCalls()
            loadConfigs(panel, node)
            assertTrue(cli.calls().isEmpty())
        }

    fun `test failed forced config reload preserves previously loaded children`() =
        withPanel { panel, cli ->
            val node = projectNodes(panel).single()
            val environment = node.getChildAt(0)
            cli.clearCalls()
            cli.setMode("config-failure")
            loadConfigs(panel, node, force = true)
            awaitConfigLoads(panel)
            assertTrue(node.loaded)
            assertFalse(node.configLoadFailed)
            assertSame(environment, node.getChildAt(0))
            assertEquals(1, cli.callCount("configs"))
        }

    fun `test superseded config success and failure cannot replace newer configs`() {
        for (mode in listOf("blocked-stale-configs", "blocked-config-failure")) {
            withPanel("empty-setup") { panel, cli ->
                val node = projectNodes(panel).single()
                withPendingConfigs(panel, cli, node, mode) {
                    assertTrue(node.loading)
                    loadConfigs(panel, node)
                    assertEquals(1, cli.callCount("configs"))
                    cli.setMode("empty-setup")
                    panel.refresh(CliOperation.TREE_REFRESH_BUTTON)
                    awaitRefresh(panel)
                    loadConfigs(panel, node)
                    awaitConfigLoads(panel)
                    assertTrue(node.loaded)
                }
                assertTrue(node.loaded)
                assertFalse(node.loading)
                assertFalse(node.configLoadFailed)
                val environment = node.getChildAt(0) as EnvironmentNode
                assertEquals("dev", (environment.getChildAt(0) as ConfigNode).config.name)
                assertEquals(2, cli.callCount("configs"))
            }
        }
    }

    fun `test config callbacks ignore nodes removed from the tree`() {
        for (mode in listOf("blocked-configs", "blocked-config-failure")) {
            withPanel("empty-setup") { panel, cli ->
                val node = projectNodes(panel).single()
                withPendingConfigs(panel, cli, node, mode) {
                    val model = field("treeModel").get(panel) as javax.swing.tree.DefaultTreeModel
                    DopplerTreeUpdater(model).projects(emptyList())
                }
                assertNull(node.parent)
                assertFalse(node.loaded)
                assertFalse(node.loading)
                assertFalse(node.configLoadFailed)
                assertTrue(node.getChildAt(0) is LoadingNode)
            }
        }
    }

    fun `test reviewed installer can be cancelled without running or deleting it`() =
        withPanel { panel, cli ->
            val path = createInstallerTempFile()
            val installer = ReviewedInstaller(path, installerSha256(ByteArray(0)))
            var confirmations = 0
            TestDialogManager.setTestDialog(
                TestDialog { message ->
                    confirmations++
                    assertTrue(message.contains(path.fileName.toString()))
                    assertTrue(message.contains(installer.sha256))
                    assertTrue(message.contains("Install for Current User"))
                    assertTrue(message.contains("administrator privileges"))
                    // Buttons: 0 = current user, 1 = system-wide (sudo), 2 = Cancel.
                    2
                },
                testRootDisposable,
            )
            try {
                field("reviewedInstaller").set(panel, installer)
                invokePanel(panel, "showInstallerReady", installer)
                assertTrue(statusText(panel).contains("Review the downloaded installer"))
                assertTrue(statusText(panel).contains("Run reviewed installer"))
                assertTrue(statusText(panel).contains("Discard downloaded installer"))
                cli.clearCalls()
                invokePanel(panel, "runReviewedInstaller", installer)
                assertEquals(1, confirmations)
                assertSame(installer, field("reviewedInstaller").get(panel))
                assertTrue(Files.exists(path))
                assertTrue(cli.calls().isEmpty())
            } finally {
                deleteInstallerTempFile(path)
            }
        }

    fun `test reviewed installer sends the chosen scope command and keeps it when the terminal fails`() =
        withPanel { panel, cli ->
            val path = createInstallerTempFile()
            val installer = ReviewedInstaller(path, installerSha256(ByteArray(0)))
            var answer = 0
            TestDialogManager.setTestDialog(TestDialog { answer }, testRootDisposable)
            val sent = mutableListOf<String>()
            var terminalAvailable = true
            panel.installCommandSender = { command ->
                sent += command
                terminalAvailable
            }
            try {
                cli.clearCalls()
                field("reviewedInstaller").set(panel, installer)
                invokePanel(panel, "runReviewedInstaller", installer)
                assertEquals(reviewedInstallerCommand(installer, InstallerScope.USER), sent.single())
                assertNull(field("reviewedInstaller").get(panel))

                answer = 1
                terminalAvailable = false
                field("reviewedInstaller").set(panel, installer)
                invokePanel(panel, "runReviewedInstaller", installer)
                assertEquals(reviewedInstallerCommand(installer, InstallerScope.SYSTEM), sent.last())
                assertSame(installer, field("reviewedInstaller").get(panel))
                assertTrue(cli.calls().isEmpty())

                answer = -1
                terminalAvailable = true
                field("reviewedInstaller").set(panel, installer)
                invokePanel(panel, "runReviewedInstaller", installer)
                assertEquals(2, sent.size)
                assertSame(installer, field("reviewedInstaller").get(panel))
            } finally {
                deleteInstallerTempFile(path)
            }
        }

    fun `test login command uses resolved executable when it is absent from PATH`() =
        withPanel { panel, cli ->
            val cliDirectory = cli.executable.parent.toString()
            assertFalse(
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .contains(cliDirectory),
            )

            val sent = mutableListOf<String>()
            panel.installCommandSender = { command ->
                sent += command
                true
            }
            cli.clearCalls()
            invokePanel(panel, "showStatusFor", DopplerStatusReport(DopplerStatus.NOT_AUTHENTICATED, null))
            clickStatusLink(panel, "Run 'doppler login' in Terminal")

            assertEquals(dopplerLoginCommand(cli.executable.toAbsolutePath().toString(), SystemInfo.isWindows), sent.single())
            assertTrue(cli.calls().isEmpty())

            DopplerSettings.getInstance().cliPath = cli.executable.resolveSibling("missing-doppler").toString()
            invokePanel(panel, "sendDopplerLogin")
            assertEquals(1, sent.size)
            assertTrue(statusText(panel).contains("The Doppler CLI could not be found."))
        }

    fun `test reviewed installer rejects stale missing and symlink files before confirmation`() =
        withPanel { panel, _ ->
            val path = createInstallerTempFile()
            val target = createInstallerTempFile()
            val installer = ReviewedInstaller(path, "a".repeat(64))
            var confirmations = 0
            TestDialogManager.setTestDialog(
                TestDialog {
                    confirmations++
                    1
                },
                testRootDisposable,
            )
            try {
                invokePanel(panel, "runReviewedInstaller", installer)
                field("reviewedInstaller").set(panel, installer)
                Files.delete(path)
                invokePanel(panel, "runReviewedInstaller", installer)
                Files.createSymbolicLink(path, target)
                invokePanel(panel, "runReviewedInstaller", installer)
                assertEquals(0, confirmations)
                assertSame(installer, field("reviewedInstaller").get(panel))
                assertTrue(Files.exists(target))
            } finally {
                Files.deleteIfExists(path)
                deleteInstallerTempFile(target)
            }
        }

    fun `test discarding a reviewed installer removes only that file and clears its state`() =
        withPanel { panel, _ ->
            val path = createInstallerTempFile()
            val installer = ReviewedInstaller(path, "a".repeat(64))
            try {
                val status = field("statusPanel").get(panel) as com.intellij.ui.components.JBPanelWithEmptyText
                status.emptyText.withUnscaledGapAfter(0)
                status.emptyText.isCenterAlignText = false
                field("reviewedInstaller").set(panel, installer)
                invokePanel(panel, "showInstallerReady", installer)
                clickStatusLink(panel, "Discard downloaded installer")
                assertFalse(Files.exists(path))
                assertNull(field("reviewedInstaller").get(panel))
                assertEquals("Downloaded installer discarded.", statusText(panel))
            } finally {
                deleteInstallerTempFile(path)
            }
        }

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
                DopplerFolderSetupState.TOPIC,
                DopplerFolderSetupListener { notifications++ },
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
        val operations = mutableListOf<CliOperation>()
        val action = DopplerTreeRefreshAction(operations::add)

        assertEquals("Refresh Doppler tree (does not reload open editors)", action.templatePresentation.text)
        assertEquals("Reload Doppler projects, configs, and folder setup", action.templatePresentation.description)
        assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)
        action.actionPerformed(TestActionEvent.createTestEvent())
        assertEquals(listOf(CliOperation.TREE_REFRESH_BUTTON), operations)
    }

    fun `test successful empty folder setup clears the bookmark`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("empty-setup") {
            val state = folderSetupState()
            state.update(DopplerFolderSetup("example", "dev"))
            var notifications = 0
            project.messageBus.connect(testRootDisposable).subscribe(
                DopplerFolderSetupState.TOPIC,
                DopplerFolderSetupListener { notifications++ },
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
                val loadThread =
                    Thread(
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

    fun `test full refresh retries a collapsed config load that fails while projects are loading`() {
        assumeFalse(SystemInfo.isWindows)
        withCli("empty-setup") { cli ->
            val panel = DopplerToolWindowPanel(project)
            val taskFailure = AtomicReference<Throwable>()

            fun startTask(
                name: String,
                action: () -> Unit,
            ) = Thread(action, name).apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> taskFailure.set(error) }
                start()
            }
            var configThread: Thread? = null
            var refreshThread: Thread? = null
            try {
                awaitRefresh(panel)
                val node = projectNodes(panel).single()
                val tree = field("tree").get(panel) as JTree
                tree.collapsePath(TreePath(node.path))
                cli.clearCalls()
                cli.setMode("blocked-config-failure")
                configThread = startTask("Doppler failing config test") { loadConfigs(panel, node) }
                awaitCall(cli, "configs")
                assertTrue(node.loading)

                cli.setMode("blocked-projects")
                refreshThread =
                    startTask("Doppler full refresh test") {
                        panel.refresh(CliOperation.TREE_REFRESH_BUTTON)
                    }
                awaitCall(cli, "projects")
                cli.releaseConfigs()
                awaitConfigLoads(panel)
                assertFalse(node.loaded)
                assertTrue(node.configLoadFailed)
                assertTrue(node.getChildAt(0).toString().startsWith("Failed to load:"))
                assertFalse(tree.isExpanded(TreePath(node.path)))

                cli.releaseProjects()
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                assertSame(node, projectNodes(panel).single())
                assertTrue("A full refresh must replace the stale config error", node.loaded)
                assertFalse(node.configLoadFailed)
                assertEquals("dev", ((node.getChildAt(0) as EnvironmentNode).getChildAt(0) as ConfigNode).config.name)
                assertEquals(2, cli.callCount("configs"))
                assertFalse(tree.isExpanded(TreePath(node.path)))
            } finally {
                cli.releaseConfigs()
                cli.releaseProjects()
                listOfNotNull(configThread, refreshThread).forEach { thread ->
                    thread.join(TimeUnit.SECONDS.toMillis(5))
                    assertFalse("Task scheduling should return", thread.isAlive)
                }
                Disposer.dispose(panel)
            }
            taskFailure.get()?.let { throw AssertionError("Could not schedule tree task", it) }
        }
    }

    private fun withPanel(
        initialMode: String = "ready",
        test: (DopplerToolWindowPanel, FakeCli) -> Unit,
    ) {
        assumeFalse(SystemInfo.isWindows)
        withCli(initialMode) { cli ->
            val panel = DopplerToolWindowPanel(project)
            try {
                awaitRefresh(panel)
                awaitConfigLoads(panel)
                test(panel, cli)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    private fun withPendingConfigs(
        panel: DopplerToolWindowPanel,
        cli: FakeCli,
        node: ProjectNode,
        mode: String,
        test: () -> Unit,
    ) {
        cli.clearCalls()
        cli.setMode(mode)
        val failure = AtomicReference<Throwable>()
        val thread =
            Thread({ loadConfigs(panel, node) }, "Doppler pending config callback test").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
                start()
            }
        try {
            awaitCall(cli, "configs")
            test()
        } finally {
            cli.releaseConfigs()
            joinWithEventDispatch(thread, "Config task should finish")
        }
        failure.get()?.let { throw AssertionError("Could not load configs", it) }
    }

    private fun invokePanel(
        panel: DopplerToolWindowPanel,
        name: String,
        vararg arguments: Any,
    ) {
        DopplerToolWindowPanel::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }
            .invoke(panel, *arguments)
    }

    private fun clickStatusLink(
        panel: DopplerToolWindowPanel,
        label: String,
    ) {
        val status = field("statusPanel").get(panel) as com.intellij.ui.components.JBPanelWithEmptyText
        status.setSize(800, 400)
        val graphics = BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            status.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val text = status.emptyText
        val rows = text.wrappedFragmentsIterable.filterIsInstance<SimpleColoredComponent>().toList()
        val index = rows.indexOfFirst { it.getCharSequence(false).toString() == label }
        assertTrue("Expected status link: $label", index >= 0)
        val point = text.pointBelow
        val gap =
            if (rows.size > 1) {
                (text.preferredSize.height - rows.sumOf { it.preferredSize.height }) / (rows.size - 1)
            } else {
                0
            }
        val y =
            point.y - text.preferredSize.height +
                rows.take(index).sumOf { it.preferredSize.height } + index * gap + rows[index].preferredSize.height / 2
        val x = point.x + if (text.isCenterAlignText) text.preferredSize.width / 2 else rows[index].preferredSize.width / 2
        val pressed = MouseEvent(status, MouseEvent.MOUSE_PRESSED, 1, 0, x, y, 1, false, MouseEvent.BUTTON1)
        val released = MouseEvent(status, MouseEvent.MOUSE_RELEASED, 2, 0, x, y, 1, false, MouseEvent.BUTTON1)
        status.mouseListeners.forEach { it.mousePressed(pressed) }
        status.mouseListeners.forEach { it.mouseReleased(released) }
    }

    private fun withCli(
        initialMode: String,
        test: (FakeCli) -> Unit,
    ) {
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

    private fun awaitCall(
        cli: FakeCli,
        command: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (cli.callCount(command) == 0 && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("Expected Doppler CLI call: $command", cli.callCount(command) > 0)
    }

    private fun awaitOpenConfig(
        projectSlug: String,
        configName: String,
    ): DopplerSecretsFile {
        val manager = FileEditorManager.getInstance(project)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var opened: DopplerSecretsFile? = null
        while (opened == null && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            opened =
                manager.openFiles
                    .filterIsInstance<DopplerSecretsFile>()
                    .firstOrNull { it.projectSlug == projectSlug && it.configName == configName }
            if (opened == null) Thread.sleep(10)
        }
        assertNotNull("Config click should open its Doppler editor", opened)
        return requireNotNull(opened)
    }

    private fun clickConfig(
        panel: DopplerToolWindowPanel,
        node: ConfigNode,
    ) {
        val tree = field("tree").get(panel) as JTree
        val environment = node.parent as EnvironmentNode
        val projectNode = environment.parent as ProjectNode
        tree.setSize(600, 600)
        tree.expandPath(TreePath(projectNode.path))
        tree.expandPath(TreePath(environment.path))
        tree.doLayout()
        val bounds = requireNotNull(tree.getPathBounds(TreePath(node.path)))
        val event =
            MouseEvent(
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
        tree.mouseListeners
            .filterIsInstance<DopplerTreeClickListener>()
            .single()
            .mouseClicked(event)
    }

    private fun isRefreshing(panel: DopplerToolWindowPanel): Boolean = field("refreshing").getBoolean(panel)

    private fun loadConfigs(
        panel: DopplerToolWindowPanel,
        node: ProjectNode,
        force: Boolean = false,
    ) {
        DopplerToolWindowPanel::class.java
            .getDeclaredMethod(
                "loadConfigs",
                ProjectNode::class.java,
                Boolean::class.javaPrimitiveType,
                CliOperation::class.java,
            ).apply { isAccessible = true }
            .invoke(panel, node, force, CliOperation.TREE_EXPAND)
    }

    private fun applyRefreshResult(
        panel: DopplerToolWindowPanel,
        generation: Int,
        projects: List<DopplerProject>,
    ) {
        val resultClass = DopplerToolWindowPanel::class.java.declaredClasses.single { it.simpleName == "RefreshResult" }
        val result =
            resultClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }.newInstance(
                DopplerStatusReport(DopplerStatus.READY, null, "replacement-context"),
                projects,
                DopplerFolderSetup("replacement", "dev"),
                false,
            )
        DopplerToolWindowPanel::class.java
            .getDeclaredMethod(
                "applyRefreshResult",
                resultClass,
                CliOperation::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            .invoke(panel, result, CliOperation.TREE_REFRESH_BUTTON, generation)
    }

    private fun projectSlugs(panel: DopplerToolWindowPanel): List<String> = projectNodes(panel).map { it.dopplerProject.slug }

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

    private fun field(name: String) =
        DopplerToolWindowPanel::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }

    private fun folderSetupState() = DopplerFolderSetupState.getInstance(project)

    private class FakeCli(
        initialMode: String,
    ) : AutoCloseable {
        private val directory = Files.createTempDirectory("doppler-tree-refresh-")
        val executable: Path = directory.resolve("doppler")
        private val modeFile = directory.resolve("mode")
        private val callsFile = directory.resolve("calls")
        private val releaseConfigsFile = directory.resolve("release-configs")
        private val releaseProjectsFile = directory.resolve("release-projects")

        init {
            Files.writeString(modeFile, initialMode)
            Files.writeString(callsFile, "")
            Files.writeString(
                executable,
                """
                #!/bin/sh
                mode="${'$'}(cat '$modeFile')"
                printf '%s\n' "${'$'}1" >> '$callsFile'
                case "${'$'}1" in
                  me)
                    if [ "${'$'}mode" = status-error ]; then
                      printf '%s' '{"error":"Account service unavailable"}'
                      exit 1
                    fi
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
                    if [ "${'$'}mode" = empty-projects ]; then
                      printf '[]'
                      exit 0
                    fi
                    if [ "${'$'}mode" = blocked-projects ]; then
                      attempts=0
                      while [ ! -f '$releaseProjectsFile' ] && [ "${'$'}attempts" -lt 1500 ]; do
                        attempts=${'$'}((attempts + 1))
                        sleep 0.01
                      done
                    fi
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
                    if [ "${'$'}mode" = blocked-configs ] || [ "${'$'}mode" = blocked-config-failure ] || [ "${'$'}mode" = blocked-stale-configs ]; then
                      attempts=0
                      while [ ! -f '$releaseConfigsFile' ] && [ "${'$'}attempts" -lt 1500 ]; do
                        attempts=${'$'}((attempts + 1))
                        sleep 0.01
                      done
                    fi
                    if [ "${'$'}mode" = blocked-config-failure ] || [ "${'$'}mode" = config-failure ]; then
                      printf '%s' '{"error":"Temporary config read failure"}'
                      exit 1
                    fi
                    if [ "${'$'}mode" = blocked-stale-configs ]; then
                      printf '%s' '[{"name":"stale","environment":"stale"}]'
                    elif [ "${'$'}mode" = other-account ]; then
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
                    if [ "${'$'}mode" = empty-setup ] || [ "${'$'}mode" = blocked-projects ]; then
                      printf '{}'
                    elif [ "${'$'}mode" = other-account ]; then
                      printf '%s' '{"project":"replacement","config":"prod"}'
                    else
                      printf '%s' '{"project":"example","config":"dev"}'
                    fi
                    ;;
                  *) exit 1 ;;
                esac
                """.trimIndent(),
            )
            check(executable.toFile().setExecutable(true))
        }

        fun clearCalls() = Files.writeString(callsFile, "")

        fun calls(): List<String> = Files.readAllLines(callsFile)

        fun callCount(command: String): Int = Files.readAllLines(callsFile).count { it == command }

        fun setMode(mode: String) = Files.writeString(modeFile, mode)

        fun releaseConfigs() = Files.writeString(releaseConfigsFile, "")

        fun releaseProjects() = Files.writeString(releaseProjectsFile, "")

        override fun close() {
            releaseConfigs()
            releaseProjects()
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
