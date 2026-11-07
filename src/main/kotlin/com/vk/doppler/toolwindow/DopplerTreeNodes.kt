package com.vk.doppler.toolwindow

import com.intellij.util.ui.tree.TreeUtil
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ProjectNode(
    var dopplerProject: DopplerProject,
) : DefaultMutableTreeNode(dopplerProject) {
    /** Configs are fetched the first time the node is expanded. */
    var loaded: Boolean = false
    var loading: Boolean = false
    internal var configLoadFailed: Boolean = false
}

class EnvironmentNode(
    val environment: String,
) : DefaultMutableTreeNode(environment)

class ConfigNode(
    val projectSlug: String,
    var config: DopplerConfig,
) : DefaultMutableTreeNode(config) {
    override fun isLeaf(): Boolean = true
}

class LoadingNode : DefaultMutableTreeNode("Loading…") {
    override fun isLeaf(): Boolean = true
}

internal class EmptyConfigsNode : DefaultMutableTreeNode("No configs available. This project may be empty, or you may not have access.") {
    override fun isLeaf(): Boolean = true
}

internal class DopplerTreeClickListener(
    private val tree: JTree,
    private val openConfig: (ConfigNode) -> Unit,
) : MouseAdapter() {
    init {
        tree.toggleClickCount = 0
    }

    override fun mouseClicked(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event)) return
        if (event.x !in 0 until tree.width) return
        val path = tree.getClosestPathForLocation(event.x, event.y) ?: return
        val bounds = tree.getPathBounds(path) ?: return
        if (event.y !in bounds.y until bounds.y + bounds.height) return
        val node = path.lastPathComponent
        when (node) {
            is ConfigNode -> {
                if (event.clickCount == 1) {
                    tree.selectionPath = path
                    openConfig(node)
                }
            }

            is ProjectNode, is EnvironmentNode -> {
                toggleBranch(event, path)
            }
        }
    }

    private fun toggleBranch(
        event: MouseEvent,
        path: TreePath,
    ) {
        val bounds = tree.getPathBounds(path) ?: return
        if (!bounds.contains(event.point)) {
            val control = TreeUtil.getExpandControlRange(tree, path) ?: return
            if (event.x <= control.to || event.x >= bounds.x) return
            if (TreeUtil.isLocationInExpandControl(tree, path, event.x, event.y)) return
            tree.selectionPath = path
        }
        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
    }
}

internal class DopplerTreeState(
    tree: JTree,
) {
    private val selected = tree.selectionPath?.identity()
    private val viewport = tree.parent as? JViewport
    private val viewPosition = viewport?.viewPosition
    private val expanded =
        tree
            .getExpandedDescendants(TreePath(tree.model.root))
            ?.toList()
            ?.mapNotNull { it.identity() }
            ?.toSet()
            .orEmpty()

    fun restore(tree: JTree) {
        try {
            restorePaths(tree)
        } finally {
            // Loading children must not scroll back to a selection left on another branch.
            if (viewport?.view === tree && viewPosition != null) viewport.viewPosition = viewPosition
        }
    }

    private fun restorePaths(tree: JTree) {
        val root = tree.model.root as DefaultMutableTreeNode
        tree.expandPath(TreePath(root.path))
        val paths =
            root
                .depthFirstEnumeration()
                .toList()
                .filterIsInstance<DefaultMutableTreeNode>()
                .map { TreePath(it.path) }
        paths.filter { it.identity() in expanded }.sortedBy { it.pathCount }.forEach(tree::expandPath)
        val selection = selected ?: return
        val target =
            paths
                .filter { path ->
                    path.identity()?.let { identity -> selection.take(identity.size) == identity } == true
                }.maxByOrNull { it.pathCount } ?: return
        tree.selectionPath = target
    }

    private fun TreePath.identity(): List<String>? {
        val identifiers =
            path.drop(1).map { node ->
                when (node) {
                    is ProjectNode -> node.dopplerProject.slug
                    is EnvironmentNode -> node.environment
                    is ConfigNode -> node.config.name
                    else -> return null
                }
            }
        return identifiers.takeIf { it.isNotEmpty() }
    }
}

internal class DopplerTreeUpdater(
    private val model: DefaultTreeModel,
) {
    fun projects(projects: List<DopplerProject>): Boolean {
        val root = model.root as DefaultMutableTreeNode
        val existing =
            root
                .children()
                .toList()
                .filterIsInstance<ProjectNode>()
                .associateBy { it.dopplerProject.slug }
        var changed = false
        val desired =
            projects.map { project ->
                val node = existing[project.slug] ?: ProjectNode(project).apply { add(LoadingNode()) }
                if (node.dopplerProject != project) {
                    node.dopplerProject = project
                    node.userObject = project
                    model.nodeChanged(node)
                    changed = true
                }
                node
            }
        return reconcile(root, desired) || changed
    }

    fun configs(
        project: ProjectNode,
        configs: List<DopplerConfig>,
    ): Boolean {
        if (configs.isEmpty()) {
            val placeholder =
                project
                    .children()
                    .toList()
                    .filterIsInstance<EmptyConfigsNode>()
                    .firstOrNull() ?: EmptyConfigsNode()
            return reconcile(project, listOf(placeholder))
        }
        val existing =
            project
                .children()
                .toList()
                .filterIsInstance<EnvironmentNode>()
                .associateBy { it.environment }
        var changed = false
        val desired =
            configs.groupBy { it.environment }.toSortedMap().map { (environment, values) ->
                val node = existing[environment] ?: EnvironmentNode(environment)
                val oldConfigs =
                    node
                        .children()
                        .toList()
                        .filterIsInstance<ConfigNode>()
                        .associateBy { it.config.name }
                val children =
                    values.sortedBy { it.name }.map { config ->
                        val child = oldConfigs[config.name] ?: ConfigNode(project.dopplerProject.slug, config)
                        if (child.config != config) {
                            child.config = config
                            child.userObject = config
                            model.nodeChanged(child)
                            changed = true
                        }
                        child
                    }
                if (node.parent == null) {
                    children.forEach(node::add)
                } else {
                    changed = reconcile(node, children) || changed
                }
                node
            }
        return reconcile(project, desired) || changed
    }

    private fun reconcile(
        parent: DefaultMutableTreeNode,
        desired: List<DefaultMutableTreeNode>,
    ): Boolean {
        var changed = false
        parent
            .children()
            .toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .filter { it !in desired }
            .forEach {
                model.removeNodeFromParent(it)
                changed = true
            }
        desired.forEachIndexed { index, node ->
            if (parent.getIndex(node) != index) {
                if (node.parent != null) model.removeNodeFromParent(node)
                model.insertNodeInto(node, parent, index)
                changed = true
            }
        }
        return changed
    }
}
