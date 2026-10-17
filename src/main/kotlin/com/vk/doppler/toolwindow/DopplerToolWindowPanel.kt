package com.vk.doppler.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.vk.doppler.DopplerIcons
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.completion.SecretReferenceService
import com.vk.doppler.editor.DopplerSecretsEditorService
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport
import com.vk.doppler.settings.DopplerConfigurable
import com.vk.doppler.settings.DopplerFolderSetupState
import com.vk.doppler.settings.DopplerFolderSetupListener
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private const val CARD_STATUS = "status"
private const val CARD_TREE = "tree"
private const val DOPPLER_TERMINAL_TAB_NAME = "Doppler CLI"
private const val INSTALL_DOCS_URL = "https://docs.doppler.com/docs/install-cli"
private const val INSTALL_SCRIPT_URL = "https://cli.doppler.com/install.sh"
private const val MAX_INSTALLER_BYTES = 2 * 1024 * 1024
private const val MAX_INSTALLER_REDIRECTS = 5

class DopplerToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val treeUpdater = DopplerTreeUpdater(treeModel)
    private val tree = Tree(treeModel)
    private val statusPanel = JBPanelWithEmptyText(BorderLayout())
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)
    private var refreshGeneration = 0
    private var refreshing = false
    private var refreshOperation = CliOperation.TREE_STARTUP
    private var disposed = false
    private var downloadingInstaller = false
    private var reviewedInstaller: ReviewedInstaller? = null
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val focusListener = DopplerFocusRefreshListener(this, ::refresh)

    init {
        configureTree()
        project.messageBus.connect(this).subscribe(DopplerFolderSetupState.TOPIC, DopplerFolderSetupListener {
            tree.repaint()
        })
        cards.add(statusPanel, CARD_STATUS)
        cards.add(JBScrollPane(tree), CARD_TREE)
        setContent(cards)
        toolbar = buildToolbar()
        focusManager.addPropertyChangeListener("focusOwner", focusListener)
        focusManager.addPropertyChangeListener("focusedWindow", focusListener)
        refresh(CliOperation.TREE_STARTUP)
    }

    override fun dispose() {
        disposed = true
        refreshGeneration++
        focusManager.removePropertyChangeListener("focusOwner", focusListener)
        focusManager.removePropertyChangeListener("focusedWindow", focusListener)
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload Doppler projects", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = refresh(CliOperation.TREE_REFRESH_BUTTON)
            })
            add(object : AnAction("Doppler Settings", "Open Doppler settings", AllIcons.General.Settings) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, DopplerConfigurable::class.java)
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.setExpandableItemsEnabled(false)
        tree.cellRenderer = DopplerTreeCellRenderer { DopplerFolderSetupState.getInstance(project).current }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? ProjectNode)?.let {
                    loadConfigs(it, operation = if (refreshing) refreshOperation else CliOperation.TREE_EXPAND)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        tree.addMouseListener(DopplerTreeClickListener(tree) { node ->
            DopplerSecretsEditorService.getInstance(project)
                .openConfig(node.projectSlug, node.config.name, node.config.environment)
        })
    }

    /** Re-checks CLI availability and authentication, then reloads the project list. */
    fun refresh(operation: CliOperation = CliOperation.TREE_REFRESH_BUTTON) {
        if (disposed || project.isDisposed || refreshing) return
        refreshing = true
        refreshOperation = operation
        val generation = ++refreshGeneration
        if (rootNode.childCount == 0) showStatus("Connecting to Doppler…", null)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading Doppler projects…", true) {
                private lateinit var report: DopplerStatusReport
                private var projects: List<DopplerProject> = emptyList()
                private var folderSetup = DopplerFolderSetup()

                override fun run(indicator: ProgressIndicator) {
                    val service = DopplerService.getInstance()
                    report = service.checkStatus(includeVersion = false, operation = operation)
                    if (report.status == DopplerStatus.READY) {
                        projects = service.listTreeProjects(operation)
                        project.basePath?.let { folder ->
                            folderSetup = try {
                                service.folderSetup(folder)
                            } catch (_: DopplerCliException) {
                                DopplerFolderSetup()
                            }
                        }
                    }
                }

                override fun onSuccess() {
                    if (project.isDisposed || generation != refreshGeneration) return
                    DopplerSecretsEditorService.invalidateCachedFiles(
                        report.authContextId.takeIf { report.status == DopplerStatus.READY },
                    )
                    SecretReferenceService.getInstance().invalidate()
                    DopplerFolderSetupState.getInstance(project).update(folderSetup)
                    if (report.status == DopplerStatus.READY) {
                        populate(projects, operation)
                    } else {
                        showStatusFor(report)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    if (project.isDisposed || generation != refreshGeneration) return
                    DopplerSecretsEditorService.invalidateCachedFiles(null)
                    showStatus("Could not reach Doppler: ${error.message}", "Retry")
                }

                override fun onFinished() {
                    if (generation == refreshGeneration) refreshing = false
                }
            },
        )
    }

    private fun populate(projects: List<DopplerProject>, operation: CliOperation) {
        val state = DopplerTreeState(tree)
        val changed = treeUpdater.projects(projects)
        if (projects.isEmpty()) {
            showStatus("No Doppler projects found for this account.", "Retry")
            return
        }
        cardLayout.show(cards, CARD_TREE)
        if (changed) state.restore(tree)
        rootNode.children().toList().filterIsInstance<ProjectNode>().forEach { node ->
            node.loading = false
            if (node.loaded || tree.isExpanded(javax.swing.tree.TreePath(node.path))) loadConfigs(node, true, operation)
        }
    }

    private fun loadConfigs(node: ProjectNode, force: Boolean = false, operation: CliOperation = CliOperation.TREE_EXPAND) {
        if (node.loading || (node.loaded && !force)) return
        node.loading = true
        val slug = node.dopplerProject.slug
        val generation = refreshGeneration

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading configs for $slug…", true) {
                private var configs: List<DopplerConfig> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    configs = DopplerService.getInstance().listConfigs(slug, operation)
                }

                override fun onSuccess() {
                    if (project.isDisposed || generation != refreshGeneration || node.parent !== rootNode) return
                    val state = DopplerTreeState(tree)
                    val changed = treeUpdater.configs(node, configs)
                    node.loaded = true
                    if (changed) state.restore(tree)
                }

                override fun onThrowable(error: Throwable) {
                    if (project.isDisposed || generation != refreshGeneration || node.parent !== rootNode) return
                    if (node.loaded) return
                    node.loaded = false
                    node.removeAllChildren()
                    node.add(DefaultMutableTreeNode("Failed to load: ${error.message}"))
                    treeModel.nodeStructureChanged(node)
                }

                override fun onFinished() {
                    if (generation == refreshGeneration) node.loading = false
                }
            },
        )
    }

    private fun showStatusFor(report: DopplerStatusReport) {
        when (report.status) {
            DopplerStatus.CLI_NOT_FOUND -> {
                statusPanel.emptyText.clear()
                statusPanel.emptyText.text = "The Doppler CLI was not found."
                statusPanel.emptyText.appendLine("Install in Terminal", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    installCliInTerminal()
                }
                statusPanel.emptyText.appendLine("Installation instructions", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    BrowserUtil.browse(INSTALL_DOCS_URL)
                }
                statusPanel.emptyText.appendLine("Set the CLI path…", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, DopplerConfigurable::class.java)
                }
                statusPanel.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { refresh(CliOperation.TREE_RETRY) }
                cardLayout.show(cards, CARD_STATUS)
            }

            DopplerStatus.NOT_AUTHENTICATED -> {
                statusPanel.emptyText.clear()
                statusPanel.emptyText.text = "Not logged in to Doppler."
                statusPanel.emptyText.appendLine("Run 'doppler login' in Terminal", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    sendInstallCommand("doppler login")
                }
                statusPanel.emptyText.appendLine("Then retry.")
                statusPanel.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { refresh(CliOperation.TREE_RETRY) }
                cardLayout.show(cards, CARD_STATUS)
            }

            else -> showStatus(report.detail ?: "Could not reach Doppler.", "Retry")
        }
    }

    private fun installCliInTerminal() {
        when {
            SystemInfo.isMac -> sendInstallCommand("brew install gnupg && brew install dopplerhq/cli/doppler")
            SystemInfo.isWindows -> sendInstallCommand("winget install doppler.doppler")
            SystemInfo.isLinux -> {
                val option = detectLinuxInstallerOption()
                if (option == null) {
                    reviewLinuxInstaller()
                } else sendInstallCommand(option.command)
            }

            else -> reviewLinuxInstaller()
        }
    }

    private fun detectLinuxInstallerOption(): LinuxInstallerOption? {
        val osRelease = runCatching { Files.readAllLines(Path.of("/etc/os-release")) }.getOrNull() ?: return null
        return linuxInstallerOption(osRelease)
    }

    private fun reviewLinuxInstaller() {
        if (downloadingInstaller) return
        downloadingInstaller = true
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Downloading Doppler installer for review…", true) {
                private lateinit var installer: ReviewedInstaller

                override fun run(indicator: ProgressIndicator) {
                    installer = downloadInstaller()
                }

                override fun onSuccess() {
                    if (project.isDisposed || disposed) {
                        Files.deleteIfExists(installer.path)
                        return
                    }
                    reviewedInstaller = installer
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(installer.path)
                    if (file == null) {
                        Files.deleteIfExists(installer.path)
                        reviewedInstaller = null
                        showStatus("Could not open the downloaded installer for review.", null)
                        return
                    }
                    OpenFileDescriptor(project, file).navigate(true)
                    showInstallerReady(installer)
                }

                override fun onThrowable(error: Throwable) {
                    if (!project.isDisposed && !disposed) {
                        showStatus("Could not download the Doppler installer: ${error.message}", null)
                    }
                }

                override fun onFinished() {
                    downloadingInstaller = false
                }
            },
        )
    }

    private fun downloadInstaller(): ReviewedInstaller {
        var url = URI(INSTALL_SCRIPT_URL).toURL()
        for (redirect in 0..MAX_INSTALLER_REDIRECTS) {
            require(url.protocol.equals("https", ignoreCase = true)) {
                "Installer redirects must use HTTPS."
            }
            val connection = url.openConnection() as? HttpsURLConnection
                ?: error("Installer URL must use HTTPS.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                when (val response = connection.responseCode) {
                    in 300..399 -> {
                        require(redirect < MAX_INSTALLER_REDIRECTS) { "Too many installer redirects." }
                        val location = connection.getHeaderField("Location")
                            ?: error("Installer redirect did not include a location.")
                        url = url.toURI().resolve(location).toURL()
                    }

                    HttpURLConnection.HTTP_OK -> {
                        val bytes = connection.inputStream.use { it.readNBytes(MAX_INSTALLER_BYTES + 1) }
                        require(bytes.isNotEmpty()) { "Downloaded installer is empty." }
                        require(bytes.size <= MAX_INSTALLER_BYTES) { "Downloaded installer is too large." }
                        val sha256 = installerSha256(bytes)
                        val path = Files.createTempFile("doppler-installer-", ".sh")
                        try {
                            Files.write(path, bytes)
                            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
                            return ReviewedInstaller(path, sha256)
                        } catch (error: Throwable) {
                            Files.deleteIfExists(path)
                            throw error
                        }
                    }

                    else -> error("Installer download failed with HTTP status $response.")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many installer redirects.")
    }

    private fun showInstallerReady(installer: ReviewedInstaller) {
        statusPanel.emptyText.clear()
        statusPanel.emptyText.text = "Review the downloaded installer in the editor before running it."
        statusPanel.emptyText.appendLine("Run reviewed installer…", SimpleTextAttributes.LINK_ATTRIBUTES) {
            runReviewedInstaller(installer)
        }
        statusPanel.emptyText.appendLine("Discard downloaded installer", SimpleTextAttributes.LINK_ATTRIBUTES) {
            if (reviewedInstaller == installer) {
                Files.deleteIfExists(installer.path)
                reviewedInstaller = null
                showStatus("Downloaded installer discarded.", null)
            }
        }
        cardLayout.show(cards, CARD_STATUS)
    }

    private fun runReviewedInstaller(installer: ReviewedInstaller) {
        if (reviewedInstaller != installer || !Files.isRegularFile(installer.path, LinkOption.NOFOLLOW_LINKS)) return
        val choice = Messages.showDialog(
            project,
            "This will run the downloaded file you reviewed (${installer.path.fileName}) with administrator privileges (sudo). " +
                "Continue only if you trust its contents.",
            "Run Reviewed Doppler Installer",
            arrayOf("Open Terminal", "Cancel"),
            1,
            Messages.getWarningIcon(),
        )
        if (choice != 0) return
        if (!sendInstallCommand(reviewedInstallerCommand(installer))) {
            return
        }
        reviewedInstaller = null
    }

    private fun sendInstallCommand(command: String): Boolean =
        runCatching {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            val terminalTab = tabsManager.tabs.lastOrNull {
                it.content.displayName == DOPPLER_TERMINAL_TAB_NAME || it.content.displayName == "Install Doppler CLI"
            }
                ?: tabsManager.createTabBuilder()
                    .workingDirectory(project.basePath ?: System.getProperty("user.home"))
                    .tabName(DOPPLER_TERMINAL_TAB_NAME)
                    .requestFocus(true)
                    .deferSessionStartUntilUiShown(true)
                    .createTab()
            terminalTab.content.displayName = DOPPLER_TERMINAL_TAB_NAME
            terminalTab.content.manager?.setSelectedContent(terminalTab.content)
            ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal")
                ?.activate(null, true)
            terminalTab.view.createSendTextBuilder().shouldExecute().send(command)
            true
        }.onFailure { error ->
            showStatus("Could not open terminal: ${error.message}", "Retry")
        }.getOrDefault(false)

    private fun showStatus(message: String, actionLabel: String?) {
        statusPanel.emptyText.clear()
        statusPanel.emptyText.text = message
        if (actionLabel != null) {
            statusPanel.emptyText.appendLine(actionLabel, SimpleTextAttributes.LINK_ATTRIBUTES) { refresh(CliOperation.TREE_RETRY) }
        }
        cardLayout.show(cards, CARD_STATUS)
    }
}

internal class DopplerFocusRefreshListener(
    private val panel: Component,
    private val refresh: (CliOperation) -> Unit,
) : PropertyChangeListener {
    private var focusedInside = false

    override fun propertyChange(event: PropertyChangeEvent) {
        if (event.propertyName == "focusedWindow") {
            val window = event.newValue as? Component ?: return
            if (panel.isShowing && SwingUtilities.isDescendingFrom(panel, window)) refresh(CliOperation.TREE_WINDOW_FOCUS)
            return
        }
        val focused = event.newValue as? Component ?: return
        val previous = event.oldValue as? Component
        val wasInside = previous?.let { SwingUtilities.isDescendingFrom(it, panel) } ?: focusedInside
        focusedInside = SwingUtilities.isDescendingFrom(focused, panel)
        if (focusedInside && !wasInside) refresh(CliOperation.TREE_PANEL_FOCUS)
    }
}

internal class DopplerTreeCellRenderer(
    private val folderSetup: () -> DopplerFolderSetup = { DopplerFolderSetup() },
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        toolTipText = null
        when (value) {
            is ProjectNode -> {
                icon = DopplerIcons.Project
                append(value.dopplerProject.displayName)
                if (folderSetup().matchesProject(value.dopplerProject.slug)) markFolderSetup()
                value.dopplerProject.description?.takeIf { it.isNotBlank() }?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val details = listOfNotNull(toolTipText, it)
                        .joinToString("<br><br>") { text -> StringUtil.escapeXmlEntities(text) }
                    toolTipText = "<html><div width='320'>$details</div></html>"
                }
            }

            is EnvironmentNode -> {
                icon = DopplerIcons.Environment
                append(value.environment)
            }

            is ConfigNode -> {
                icon = if (selected) DopplerIcons.ConfigSelected else DopplerIcons.Config
                append(value.config.name)
                if (folderSetup().matchesConfig(value.projectSlug, value.config.name)) markFolderSetup()
            }

            else -> append(value?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun markFolderSetup() {
        val nodeIcon = icon
        icon = com.intellij.ui.RowIcon(2).apply {
            setIcon(nodeIcon, 0)
            setIcon(AllIcons.Nodes.Bookmark, 1)
        }
        toolTipText = "Configured for this project folder in the Doppler CLI (may be inherited from a parent folder)"
    }
}
