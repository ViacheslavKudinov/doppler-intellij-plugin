package com.vk.doppler.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import com.vk.doppler.DopplerIcons
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.editor.DopplerSecretsEditorService
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport
import com.vk.doppler.settings.DopplerConfigurable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseEvent
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
private const val INSTALL_DOCS_URL = "https://docs.doppler.com/docs/install-cli"

class DopplerToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val statusPanel = JBPanelWithEmptyText(BorderLayout())
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    init {
        configureTree()
        cards.add(statusPanel, CARD_STATUS)
        cards.add(JBScrollPane(tree), CARD_TREE)
        setContent(cards)
        toolbar = buildToolbar()
        refresh()
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Reload Doppler projects", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = refresh()
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
        tree.cellRenderer = DopplerTreeCellRenderer()

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? ProjectNode)?.let(::loadConfigs)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (clickCount != 1 || !SwingUtilities.isLeftMouseButton(event)) return false
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                val node = path.lastPathComponent as? ConfigNode ?: return false
                DopplerSecretsEditorService.getInstance(project).openConfig(node.projectSlug, node.config.name)
                return true
            }
        }.installOn(tree)
    }

    /** Re-checks CLI availability and authentication, then reloads the project list. */
    fun refresh() {
        showStatus("Connecting to Doppler…", null)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading Doppler projects…", true) {
                private lateinit var report: DopplerStatusReport
                private var projects: List<DopplerProject> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    val service = DopplerService.getInstance()
                    report = service.checkStatus()
                    if (report.status == DopplerStatus.READY) {
                        projects = service.listProjects()
                    }
                }

                override fun onSuccess() {
                    if (report.status == DopplerStatus.READY) {
                        populate(projects)
                    } else {
                        showStatusFor(report)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    showStatus("Could not reach Doppler: ${error.message}", "Retry")
                }
            },
        )
    }

    private fun populate(projects: List<DopplerProject>) {
        rootNode.removeAllChildren()
        if (projects.isEmpty()) {
            showStatus("No Doppler projects found for this account.", "Retry")
            return
        }
        projects.forEach { dopplerProject ->
            val node = ProjectNode(dopplerProject)
            node.add(LoadingNode())
            rootNode.add(node)
        }
        treeModel.reload()
        cardLayout.show(cards, CARD_TREE)
    }

    private fun loadConfigs(node: ProjectNode) {
        if (node.loaded) return
        node.loaded = true
        val slug = node.dopplerProject.slug

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading configs for $slug…", true) {
                private var configs: List<DopplerConfig> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    configs = DopplerService.getInstance().listConfigs(slug)
                }

                override fun onSuccess() {
                    node.removeAllChildren()
                    configs.groupBy { it.environment }
                        .toSortedMap()
                        .forEach { (environment, environmentConfigs) ->
                            val envNode = EnvironmentNode(environment)
                            environmentConfigs.forEach { envNode.add(ConfigNode(slug, it)) }
                            node.add(envNode)
                        }
                    treeModel.nodeStructureChanged(node)
                    tree.expandPath(javax.swing.tree.TreePath(treeModel.getPathToRoot(node)))
                }

                override fun onThrowable(error: Throwable) {
                    node.loaded = false
                    node.removeAllChildren()
                    node.add(DefaultMutableTreeNode("Failed to load: ${error.message}"))
                    treeModel.nodeStructureChanged(node)
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
                statusPanel.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { refresh() }
                cardLayout.show(cards, CARD_STATUS)
            }

            DopplerStatus.NOT_AUTHENTICATED -> {
                statusPanel.emptyText.clear()
                statusPanel.emptyText.text = "Not logged in to Doppler."
                statusPanel.emptyText.appendLine("Run 'doppler login' in a terminal, then retry.")
                statusPanel.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { refresh() }
                cardLayout.show(cards, CARD_STATUS)
            }

            else -> showStatus(report.detail ?: "Could not reach Doppler.", "Retry")
        }
    }

    private fun installCliInTerminal() {
        val command = when {
            SystemInfo.isMac -> "brew install gnupg && brew install dopplerhq/cli/doppler"
            SystemInfo.isWindows -> "winget install doppler.doppler"
            else -> "(curl -Ls --tlsv1.2 --proto '=https' --retry 3 https://cli.doppler.com/install.sh || wget -t 3 -qO- https://cli.doppler.com/install.sh) | sudo sh"
        }
        runCatching {
            val manager = TerminalToolWindowManager.getInstance(project)
            val terminal = ShellTerminalWidget.toShellJediTermWidgetOrThrow(
                manager.createNewSession(),
            )
            terminal.executeCommand(command)
        }.onFailure { error ->
            showStatus("Could not open terminal: ${error.message}", "Retry")
        }
    }

    private fun showStatus(message: String, actionLabel: String?) {
        statusPanel.emptyText.clear()
        statusPanel.emptyText.text = message
        if (actionLabel != null) {
            statusPanel.emptyText.appendLine(actionLabel, SimpleTextAttributes.LINK_ATTRIBUTES) { refresh() }
        }
        cardLayout.show(cards, CARD_STATUS)
    }
}

private class DopplerTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (value) {
            is ProjectNode -> {
                icon = DopplerIcons.Project
                append(value.dopplerProject.displayName)
                value.dopplerProject.description?.takeIf { it.isNotBlank() }?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is EnvironmentNode -> {
                icon = DopplerIcons.Environment
                append(value.environment)
            }

            is ConfigNode -> {
                icon = if (selected) DopplerIcons.ConfigSelected else DopplerIcons.Config
                append(value.config.name)
            }

            else -> append(value?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
