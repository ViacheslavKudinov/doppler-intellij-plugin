package com.vk.doppler.toolwindow

import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.DopplerIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.beans.PropertyChangeEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener

class DopplerTreeStateTest {
    @Test
    fun `project descriptions are escaped and preserve folder setup tooltips`() {
        SwingUtilities.invokeAndWait {
            val renderer = DopplerTreeCellRenderer { com.vk.doppler.cli.DopplerFolderSetup("backend", "dev") }
            val tree = JTree()
            val node = ProjectNode(DopplerProject("backend", "Backend", "A <service> & \"details\""))
            renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
            assertTrue(renderer.getCharSequence(false).toString().contains("A <service>"))
            assertTrue(renderer.toolTipText.contains("Configured for this project folder"))
            assertTrue(renderer.toolTipText.contains("A &lt;service&gt; &amp; &quot;details&quot;"))
            assertTrue(renderer.icon is com.intellij.ui.RowIcon)

            renderer.getTreeCellRendererComponent(
                tree, ProjectNode(DopplerProject("other", "Other", " ")), false, false, true, 0, false,
            )
            assertEquals(null, renderer.toolTipText)
            assertSame(DopplerIcons.Project, renderer.icon)
        }
    }

    @Test
    fun `renderer handles environments selected configs and placeholders`() {
        SwingUtilities.invokeAndWait {
            val renderer = DopplerTreeCellRenderer()
            val tree = JTree()
            renderer.getTreeCellRendererComponent(tree, EnvironmentNode("production"), false, false, true, 0, false)
            assertEquals("production", renderer.getCharSequence(false).toString())
            assertSame(DopplerIcons.Environment, renderer.icon)
            renderer.getTreeCellRendererComponent(tree, ConfigNode("backend", DopplerConfig("prod", "production")),
                true, false, true, 0, false)
            assertSame(DopplerIcons.ConfigSelected, renderer.icon)
            renderer.getTreeCellRendererComponent(tree, LoadingNode(), false, false, true, 0, false)
            assertEquals(LoadingNode().toString(), renderer.getCharSequence(false).toString())
            assertEquals(null, renderer.toolTipText)
        }
    }

    @Test
    fun `folder bookmark follows setup and does not leak to other tree rows`() {
        SwingUtilities.invokeAndWait {
            var setup = com.vk.doppler.cli.DopplerFolderSetup("backend", "dev")
            val renderer = DopplerTreeCellRenderer { setup }
            val tree = JTree()
            fun render(node: Any) = renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
            val project = ProjectNode(DopplerProject("backend", "Backend", null))
            val config = ConfigNode("backend", DopplerConfig("dev", "dev"))
            render(project)
            assertTrue(renderer.icon is com.intellij.ui.RowIcon)
            render(config)
            assertTrue(renderer.icon is com.intellij.ui.RowIcon)
            assertTrue(renderer.toolTipText.contains("Configured for this project folder"))
            render(ConfigNode("other", DopplerConfig("dev", "dev")))
            assertFalse(renderer.icon is com.intellij.ui.RowIcon)
            assertEquals(null, renderer.toolTipText)
            setup = com.vk.doppler.cli.DopplerFolderSetup()
            render(config)
            assertFalse(renderer.icon is com.intellij.ui.RowIcon)
            render(project)
            assertFalse(renderer.icon is com.intellij.ui.RowIcon)
        }
    }

    @Test
    fun `consecutive clicks toggle selected project and environment labels every time`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode()
            val project = projectNode()
            root.add(project)
            val tree = JTree(root).apply {
                isRootVisible = false
                setSize(400, 300)
            }
            val listener = DopplerTreeClickListener(tree) { error("Branch clicks must not open configs") }
            val projectPath = TreePath(project.path)
            tree.selectionPath = projectPath
            tree.collapsePath(projectPath)
            for (clickCount in 1..3) {
                clickLabel(tree, listener, projectPath, clickCount)
                assertEquals(clickCount % 2 == 1, tree.isExpanded(projectPath))
                assertEquals(projectPath, tree.selectionPath)
            }
            val environmentPath = TreePath((project.getChildAt(0) as EnvironmentNode).path)
            tree.selectionPath = environmentPath
            tree.collapsePath(environmentPath)
            for (clickCount in 1..3) {
                clickLabel(tree, listener, environmentPath, clickCount)
                assertEquals(clickCount % 2 == 1, tree.isExpanded(environmentPath))
            }
            assertEquals(0, tree.toggleClickCount)
        }
    }

    @Test
    fun `config clicks still open once and clicks outside labels do not toggle branches`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode()
            val project = projectNode()
            root.add(project)
            val tree = JTree(root).apply {
                isRootVisible = false
                setSize(400, 300)
            }
            var opens = 0
            val listener = DopplerTreeClickListener(tree) { opens++ }
            val environment = project.getChildAt(0) as EnvironmentNode
            val configPath = TreePath((environment.getChildAt(0) as ConfigNode).path)
            tree.expandPath(TreePath(project.path))
            tree.expandPath(TreePath(environment.path))
            clickLabel(tree, listener, configPath, 1)
            clickLabel(tree, listener, configPath, 2)
            assertEquals(1, opens)
            val projectPath = TreePath(project.path)
            val bounds = requireNotNull(tree.getPathBounds(projectPath))
            listener.mouseClicked(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0,
                bounds.x - 1, bounds.y + bounds.height / 2, 1, false, MouseEvent.BUTTON1))
            assertTrue(tree.isExpanded(projectPath))
        }
    }

    private fun clickLabel(tree: JTree, listener: DopplerTreeClickListener, path: TreePath, clickCount: Int) {
        val bounds = requireNotNull(tree.getPathBounds(path))
        listener.mouseClicked(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0,
            bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, clickCount, false, MouseEvent.BUTTON1))
    }

    @Test
    fun `initial project insertion is visible in an empty tree`() {
        SwingUtilities.invokeAndWait {
            val model = DefaultTreeModel(DefaultMutableTreeNode())
            val tree = JTree(model).apply { isRootVisible = false }
            val state = DopplerTreeState(tree)
            DopplerTreeUpdater(model).projects(listOf(DopplerProject("backend", "Backend", null)))
            state.restore(tree)
            assertEquals("The initial project must be visible", 1, tree.rowCount)
        }
    }

    @Test
    fun `identical refresh emits no model events and retains selected nodes`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode()
            val model = DefaultTreeModel(root)
            val updater = DopplerTreeUpdater(model)
            val projects = listOf(DopplerProject("backend", "Backend", null))
            val configs = listOf(DopplerConfig("dev_personal", "dev"))
            updater.projects(projects)
            val project = root.getChildAt(0) as ProjectNode
            updater.configs(project, configs)
            val environment = project.getChildAt(0) as EnvironmentNode
            val config = environment.getChildAt(0) as ConfigNode
            val tree = JTree(model).apply { isRootVisible = false }
            tree.selectionPath = TreePath(config.path)
            var events = 0
            model.addTreeModelListener(object : TreeModelListener {
                override fun treeNodesChanged(event: TreeModelEvent) { events++ }
                override fun treeNodesInserted(event: TreeModelEvent) { events++ }
                override fun treeNodesRemoved(event: TreeModelEvent) { events++ }
                override fun treeStructureChanged(event: TreeModelEvent) { events++ }
            })

            assertFalse(updater.projects(projects))
            assertFalse(updater.configs(project, configs))
            assertEquals(0, events)
            assertSame(config, tree.lastSelectedPathComponent)
            assertTrue(tree.isExpanded(TreePath(environment.path)))
        }
    }

    @Test
    fun `refresh reconciles added deleted and renamed entries without replacing unchanged branches`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode()
            val updater = DopplerTreeUpdater(DefaultTreeModel(root))
            updater.projects(listOf(DopplerProject("backend", "Backend", null), DopplerProject("old", "Old", null)))
            val project = root.getChildAt(0) as ProjectNode
            updater.configs(project, listOf(DopplerConfig("keep", "dev"), DopplerConfig("old", "dev")))
            val environment = project.getChildAt(0) as EnvironmentNode
            val kept = environment.getChildAt(0)

            assertTrue(updater.projects(listOf(DopplerProject("backend", "Renamed", null), DopplerProject("new", "New", null))))
            assertSame(project, root.getChildAt(0))
            assertEquals("Renamed", project.dopplerProject.name)
            assertTrue(updater.configs(project, listOf(DopplerConfig("keep", "dev"), DopplerConfig("renamed", "dev"), DopplerConfig("stg", "stg"))))
            assertSame(environment, project.getChildAt(0))
            assertSame(kept, environment.getChildAt(0))
            assertEquals("renamed", (environment.getChildAt(1) as ConfigNode).config.name)
            assertEquals("stg", (project.getChildAt(1) as EnvironmentNode).environment)
            assertTrue(updater.configs(project, listOf(DopplerConfig("stg", "stg"))))
            assertEquals(1, project.childCount)
            assertTrue(updater.projects(emptyList()))
            assertEquals(0, root.childCount)
        }
    }

    @Test
    fun `returning to the IDE refreshes a visible panel without changing its focus owner`() {
        SwingUtilities.invokeAndWait {
            var visible = true
            val panel = object : JPanel() {
                override fun isShowing() = visible
            }
            val window = JPanel().apply { add(panel) }
            val otherWindow = JPanel()
            val tree = JTree().also { panel.add(it) }
            var refreshes = 0
            val operations = mutableListOf<com.vk.doppler.cli.CliOperation>()
            val listener = DopplerFocusRefreshListener(panel) { refreshes++; operations += it }

            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", null, tree))
            assertEquals(1, refreshes)
            listener.propertyChange(PropertyChangeEvent(panel, "focusedWindow", window, null))
            listener.propertyChange(PropertyChangeEvent(panel, "focusedWindow", null, window))
            assertEquals(2, refreshes)
            listener.propertyChange(PropertyChangeEvent(panel, "focusedWindow", window, otherWindow))
            assertEquals(2, refreshes)
            visible = false
            listener.propertyChange(PropertyChangeEvent(panel, "focusedWindow", otherWindow, window))
            assertEquals(2, refreshes)
            assertEquals(listOf(com.vk.doppler.cli.CliOperation.TREE_PANEL_FOCUS,
                com.vk.doppler.cli.CliOperation.TREE_WINDOW_FOCUS), operations)
        }
    }

    @Test
    fun `focus refresh occurs on entry but not navigation within the panel`() {
        SwingUtilities.invokeAndWait {
            val panel = JPanel()
            val tree = JTree()
            val toolbarButton = JButton()
            panel.add(tree)
            panel.add(toolbarButton)
            val outside = JButton()
            var refreshes = 0
            val listener = DopplerFocusRefreshListener(panel) { refreshes++ }

            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", outside, tree))
            assertEquals(1, refreshes)
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", tree, toolbarButton))
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", toolbarButton, tree))
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", tree, null))
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", null, toolbarButton))
            assertEquals(1, refreshes)
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", tree, outside))
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", outside, null))
            assertEquals(1, refreshes)
            listener.propertyChange(PropertyChangeEvent(panel, "focusOwner", null, tree))
            assertEquals(2, refreshes)
        }
    }

    @Test
    fun `focus refresh reuses a recent tree only for the same Doppler account`() {
        var now = 1_000L
        val interval = 30_000L
        val gate = DopplerTreeRefreshGate({ now }, interval)

        assertFalse(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_PANEL_FOCUS, "account-a"))
        gate.recordTreeReload("account-a")
        assertTrue(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_PANEL_FOCUS, "account-a"))
        assertTrue(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_WINDOW_FOCUS, "account-a"))
        assertFalse(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_PANEL_FOCUS, "account-b"))

        assertFalse(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_REFRESH_BUTTON, "account-a"))

        now += interval
        assertFalse(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_WINDOW_FOCUS, "account-a"))

        now = 1_000L
        gate.invalidate()
        assertFalse(gate.canReuseTree(com.vk.doppler.cli.CliOperation.TREE_PANEL_FOCUS, "account-a"))
    }

    @Test
    fun `refresh restores config selection after lazy loading with new node instances`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode()
            val original = projectNode()
            root.add(original)
            val tree = JTree(root).apply { isRootVisible = false }
            val environment = original.getChildAt(0) as EnvironmentNode
            tree.expandPath(TreePath(original.path))
            tree.expandPath(TreePath(environment.path))
            tree.selectionPath = TreePath((environment.getChildAt(0) as ConfigNode).path)
            val state = DopplerTreeState(tree)

            root.removeAllChildren()
            val replacement = ProjectNode(DopplerProject("backend", "Renamed project", null))
            replacement.add(LoadingNode())
            root.add(replacement)
            (tree.model as DefaultTreeModel).reload()
            state.restore(tree)
            assertEquals(replacement, tree.lastSelectedPathComponent)
            assertTrue(tree.isExpanded(TreePath(replacement.path)))

            replacement.removeAllChildren()
            val newEnvironment = EnvironmentNode("dev")
            val newConfig = ConfigNode("backend", DopplerConfig("dev_personal", "dev"))
            newEnvironment.add(newConfig)
            replacement.add(newEnvironment)
            (tree.model as DefaultTreeModel).nodeStructureChanged(replacement)
            state.restore(tree)
            assertEquals(newConfig, tree.lastSelectedPathComponent)
            assertTrue(tree.isExpanded(TreePath(newEnvironment.path)))
        }
    }

    @Test
    fun `refresh preserves project and environment selection and collapsed branches`() {
        SwingUtilities.invokeAndWait {
            for (selectEnvironment in listOf(false, true)) {
                val root = DefaultMutableTreeNode()
                val original = projectNode()
                root.add(original)
                val tree = JTree(root).apply { isRootVisible = false }
                val selected = if (selectEnvironment) original.getChildAt(0) as EnvironmentNode else original
                tree.selectionPath = TreePath(selected.path)
                val state = DopplerTreeState(tree)
                root.removeAllChildren()
                val replacement = projectNode()
                root.add(replacement)
                (tree.model as DefaultTreeModel).reload()
                state.restore(tree)
                assertEquals(if (selectEnvironment) replacement.getChildAt(0) else replacement, tree.lastSelectedPathComponent)
                assertFalse(tree.isExpanded(TreePath((replacement.getChildAt(0) as EnvironmentNode).path)))
            }
        }
    }

    private fun projectNode(): ProjectNode = ProjectNode(DopplerProject("backend", "Backend", null)).apply {
        add(EnvironmentNode("dev").apply {
            add(ConfigNode("backend", DopplerConfig("dev_personal", "dev")))
        })
    }
}
