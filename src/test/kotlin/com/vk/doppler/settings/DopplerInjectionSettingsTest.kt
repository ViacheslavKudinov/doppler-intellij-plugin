package com.vk.doppler.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.vk.doppler.cli.DopplerNameTransformer
import com.vk.doppler.injection.SecretNameMapping
import org.jdom.Element
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

class DopplerInjectionSettingsTest : BasePlatformTestCase() {
    private lateinit var savedConsent: DopplerInjectionConsent.State

    override fun setUp() {
        super.setUp()
        savedConsent = DopplerInjectionConsent.getInstance().state
        DopplerInjectionConsent.getInstance().loadState(DopplerInjectionConsent.State())
    }

    override fun tearDown() {
        try {
            DopplerInjectionConsent.getInstance().loadState(savedConsent)
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultsAndPersistenceContainOnlyInjectionPreferences() {
        val settings = DopplerInjectionSettings(project)
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        settings.enabled = true
        for (transformer in DopplerNameTransformer.entries) {
            settings.nameTransformer = transformer
            val serialized = XmlSerializer.serialize(settings.state)
            val restored = DopplerInjectionSettings(project)
            restored.loadState(XmlSerializer.deserialize(serialized, DopplerInjectionSettings.State::class.java))
            assertEquals(settings.state, restored.state)
            assertTrue(
                serialized.getChildren("option").all {
                    it.getAttributeValue("name") in
                        setOf("enabled", "nameTransformer", "customMappings")
                },
            )
        }
    }

    fun testCustomMappingsPersistForEveryTransformerAndAreCopiedNotShared() {
        val settings = DopplerInjectionSettings(project)
        assertEquals(emptyList<SecretNameMapping>(), settings.customMappings)
        val pairs = listOf(SecretNameMapping("DB_URL", "DATABASE_URL"), SecretNameMapping("MISSING", "OTHER"))
        settings.customMappings = pairs
        for (transformer in DopplerNameTransformer.entries) {
            settings.nameTransformer = transformer
            val restored = DopplerInjectionSettings(project)
            restored.loadState(
                XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), DopplerInjectionSettings.State::class.java),
            )
            assertEquals(transformer, restored.nameTransformer)
            assertEquals(pairs, restored.customMappings)
        }
        val loaded = DopplerInjectionSettings(project)
        loaded.loadState(settings.state)
        loaded.state.customMappings[0].newName = "CHANGED"
        loaded.customMappings[1].newName = "CHANGED"
        assertEquals(pairs, settings.customMappings)
        assertEquals("DATABASE_URL", settings.customMappings[0].newName)
    }

    fun testLegacyWorkspaceOptInCannotEnableInjectionWithoutLocalApproval() {
        val legacy =
            Element("state")
                .addContent(Element("option").setAttribute("name", "enabled").setAttribute("value", "true"))
                .addContent(Element("option").setAttribute("name", "durationMinutes").setAttribute("value", "12"))
        val settings = DopplerInjectionSettings(project)
        settings.loadState(XmlSerializer.deserialize(legacy, DopplerInjectionSettings.State::class.java))
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        val serialized = XmlSerializer.serialize(settings.state)
        assertEquals(
            setOf("enabled", "nameTransformer", "customMappings"),
            serialized
                .getChildren("option")
                .map {
                    it.getAttributeValue("name")
                }.toSet(),
        )
    }

    fun testWorkspaceChangesCannotGrantOrRestoreRevokedApproval() {
        val settings = DopplerInjectionSettings(project)
        settings.loadState(DopplerInjectionSettings.State(true, DopplerNameTransformer.CAMEL))
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.CAMEL, settings.nameTransformer)
        settings.enabled = true
        assertTrue(settings.enabled)
        val reopened = DopplerInjectionSettings(project)
        reopened.loadState(settings.state.copy())
        assertTrue(reopened.enabled)
        settings.enabled = false
        reopened.loadState(DopplerInjectionSettings.State(true))
        assertFalse(reopened.enabled)
    }

    fun testConsentIsUserLocalProjectScopedAndPersistsWithoutSharingMutableState() {
        val storage =
            DopplerInjectionConsent::class.java
                .getAnnotation(com.intellij.openapi.components.State::class.java)
                .storages
                .single()
        assertEquals("doppler-injection-consent.xml", storage.value)
        assertEquals(com.intellij.openapi.components.RoamingType.DISABLED, storage.roamingType)
        val consent = DopplerInjectionConsent()
        assertFalse(consent.isGranted("/synthetic/project-one"))
        consent.setGranted(null, true)
        consent.setGranted("", true)
        assertTrue(consent.state.approvedProjects.isEmpty())
        consent.setGranted("/synthetic/project-one", true)
        assertTrue(consent.isGranted("/synthetic/project-one"))
        assertFalse(consent.isGranted("/synthetic/project-two"))
        val serialized = XmlSerializer.serialize(consent.state)
        val restored = DopplerInjectionConsent()
        val state = XmlSerializer.deserialize(serialized, DopplerInjectionConsent.State::class.java)
        restored.loadState(state)
        state.approvedProjects.clear()
        restored.state.approvedProjects.clear()
        assertTrue(restored.isGranted("/synthetic/project-one"))
        restored.setGranted("/synthetic/project-one", false)
        assertFalse(restored.isGranted("/synthetic/project-one"))
    }

    fun testSettingsPageAppliesAndResetsOptInWithoutAnExpiryField() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy()
        settings.enabled = false
        settings.nameTransformer = DopplerNameTransformer.NONE
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val enabled = UIUtil.findComponentOfType(panel, JCheckBox::class.java)!!
            val transformation = UIUtil.findComponentOfType(panel, JComboBox::class.java)!!
            assertEquals(DopplerNameTransformer.entries, (0 until transformation.itemCount).map { transformation.getItemAt(it) })
            assertEquals(DopplerNameTransformer.NONE, transformation.selectedItem)
            assertFalse(enabled.isSelected)
            assertNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
            enabled.isSelected = true
            transformation.selectedItem = DopplerNameTransformer.DOTNET_ENV
            assertTrue(panel.isModified())
            panel.apply()
            assertTrue(settings.enabled)
            assertEquals(DopplerNameTransformer.DOTNET_ENV, settings.nameTransformer)
            enabled.isSelected = false
            transformation.selectedItem = DopplerNameTransformer.CAMEL
            panel.reset()
            assertTrue(enabled.isSelected)
            assertEquals(DopplerNameTransformer.DOTNET_ENV, transformation.selectedItem)
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    private fun isShown(
        component: Component,
        root: Component,
    ): Boolean {
        var current: Component? = component
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    fun testMappingTableIsShownOnlyForCustomAndPairsAreSavedAndKeptWhenAnotherOptionIsChosen() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.NONE
        settings.customMappings = emptyList()
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val transformation = UIUtil.findComponentOfType(panel, JComboBox::class.java)!!
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val model = table.model as DefaultTableModel
            assertEquals(listOf("Current secret name", "New secret name"), (0 until 2).map { model.getColumnName(it) })
            assertSame(table.columnModel, table.tableHeader.columnModel)
            val header =
                table.tableHeader.defaultRenderer.getTableCellRendererComponent(
                    table,
                    model.getColumnName(0),
                    false,
                    false,
                    -1,
                    0,
                ) as javax.swing.JComponent
            assertTrue("Column headers must be visually distinct from mapping rows", header.font.isBold)
            assertEquals(
                com.intellij.util.ui.UIUtil
                    .getPanelBackground(),
                header.background,
            )
            val headerPadding = header.insets.left
            for (column in 0 until table.columnCount) {
                val renderer = table.columnModel.getColumn(column).cellRenderer
                val cell = renderer.getTableCellRendererComponent(table, "EXAMPLE", false, false, 0, column) as javax.swing.JLabel
                assertEquals("Headers and both mapping columns must share left padding", headerPadding, cell.insets.left)
                assertNull("Destination names must not be marked as missing source secrets", cell.icon)
            }
            val scrollPane =
                javax.swing.SwingUtilities.getAncestorOfClass(
                    javax.swing.JScrollPane::class.java,
                    table,
                ) as javax.swing.JScrollPane
            val decorated = scrollPane.parent
            assertEquals("The mapping area should show more rows by default", 320, decorated.preferredSize.height)
            val layout = decorated.layout as java.awt.BorderLayout
            assertNotNull(
                "The add/remove toolbar belongs above the table header and rows",
                layout.getLayoutComponent(java.awt.BorderLayout.NORTH),
            )
            assertNull(layout.getLayoutComponent(java.awt.BorderLayout.SOUTH))
            assertFalse(isShown(table, panel))

            transformation.selectedItem = DopplerNameTransformer.CUSTOM
            assertTrue(isShown(table, panel))
            model.addRow(arrayOf(" DB_URL ", "DATABASE_URL"))
            model.addRow(arrayOf("", ""))
            model.addRow(arrayOf("MISSING", "OTHER"))
            assertTrue(panel.isModified())
            panel.apply()
            assertEquals(DopplerNameTransformer.CUSTOM, settings.nameTransformer)
            assertEquals(
                listOf(SecretNameMapping("DB_URL", "DATABASE_URL"), SecretNameMapping("MISSING", "OTHER")),
                settings.customMappings,
            )
            assertFalse(panel.isModified())

            transformation.selectedItem = DopplerNameTransformer.CAMEL
            assertFalse(isShown(table, panel))
            panel.apply()
            assertEquals(DopplerNameTransformer.CAMEL, settings.nameTransformer)
            assertEquals(2, settings.customMappings.size)

            model.removeRow(0)
            transformation.selectedItem = DopplerNameTransformer.CUSTOM
            assertTrue(panel.isModified())
            panel.reset()
            assertEquals(2, model.rowCount)
            assertEquals("DB_URL", model.getValueAt(0, 0))
            assertEquals(DopplerNameTransformer.CAMEL, transformation.selectedItem)
            assertFalse(isShown(table, panel))
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    fun testInvalidPairsBlockApplyOnlyWhileCustomIsSelected() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.NONE
        settings.customMappings = emptyList()
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val transformation = UIUtil.findComponentOfType(panel, JComboBox::class.java)!!
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val model = table.model as DefaultTableModel
            model.addRow(arrayOf("A", "X"))
            model.addRow(arrayOf("A", "Y"))
            transformation.selectedItem = DopplerNameTransformer.CUSTOM
            try {
                page.apply()
                fail("Duplicate current names must be rejected")
            } catch (_: ProcessCanceledException) {
            }
            assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
            assertTrue(settings.customMappings.isEmpty())

            model.setValueAt("B", 1, 0)
            model.setValueAt("B=C", 1, 1)
            try {
                page.apply()
                fail("Invalid environment variable names must be rejected")
            } catch (_: ProcessCanceledException) {
            }

            for (name in listOf(" VALID_NAME", "VALID_NAME ", "\tVALID_NAME", "VALID_NAME\t")) {
                assertTrue(table.editCellAt(1, 1))
                (table.editorComponent as JBTextField).text = name
                assertTrue(panel.isModified())
                try {
                    page.apply()
                    fail("Surrounding whitespace must be rejected, not trimmed")
                } catch (_: ProcessCanceledException) {
                }
                assertEquals("Rejected input must remain available for correction", name, model.getValueAt(1, 1))
                assertTrue(settings.customMappings.isEmpty())
            }

            assertTrue(page.validationMessage.isVisible)
            assertTrue(page.validationMessageTimer.isRunning)
            assertEquals(5_000, page.validationMessageTimer.initialDelay)
            page.validationMessageTimer.actionListeners.forEach {
                it.actionPerformed(java.awt.event.ActionEvent(page.validationMessageTimer, 0, "timeout"))
            }
            assertFalse(page.validationMessage.isVisible)
            assertFalse(page.validationMessageTimer.isRunning)
            assertTrue(settings.customMappings.isEmpty())

            try {
                page.apply()
                fail("Hiding the message must not allow invalid mappings")
            } catch (_: ProcessCanceledException) {
                assertTrue(page.validationMessage.isVisible)
            }
            model.setValueAt("VALID_NAME", 1, 1)
            assertFalse(page.validationMessage.isVisible)
            assertFalse(page.validationMessageTimer.isRunning)

            transformation.selectedItem = DopplerNameTransformer.NONE
            page.apply()
            assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    fun testCurrentNameOnlyOffersFetchedSecretsAndPreservesMissingMappings() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("OLD_SECRET", "NEW_SECRET"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            assertEquals("OLD_SECRET", table.model.getValueAt(0, 0))
            val cellEditor = table.columnModel.getColumn(0).cellEditor as javax.swing.DefaultCellEditor
            val editor = cellEditor.component as JComboBox<*>
            val editorModel = editor.model as DefaultComboBoxModel<String>
            refreshSecretNameSuggestions(editorModel, setOf("CURRENT_SECRET", "ANOTHER_SECRET"))
            assertFalse(editor.isEditable)
            assertEquals(listOf("ANOTHER_SECRET", "CURRENT_SECRET"), (0 until editorModel.size).map { editorModel.getElementAt(it) })
            assertTrue(table.editCellAt(0, 0))
            assertNull(editor.selectedItem)
            assertFalse(panel.isModified())
            table.cellEditor?.stopCellEditing()
            assertEquals("OLD_SECRET", table.model.getValueAt(0, 0))

            assertTrue(table.editCellAt(0, 0))
            editor.selectedItem = "CURRENT_SECRET"
            table.cellEditor?.stopCellEditing()
            assertEquals("CURRENT_SECRET", table.model.getValueAt(0, 0))
            assertTrue(panel.isModified())

            refreshSecretNameSuggestions(
                editorModel,
                knownNames = emptySet(),
            )
            assertEquals("CURRENT_SECRET", table.model.getValueAt(0, 0))
            assertEquals(0, editorModel.size)
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    fun testCheckingModifiedStateDoesNotCloseTheSecretNameEditor() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("OLD_SECRET", "NEW_SECRET"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            assertTrue(table.editCellAt(0, 0))
            val combo = table.editorComponent as JComboBox<*>
            assertFalse(combo.isEditable)

            repeat(3) {
                assertFalse("Opening the list must not change a missing mapping", panel.isModified())
                assertTrue("Dirty checking must not stop the active table editor", table.isEditing)
            }
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    fun testAddActionCountsEmptyRowsTowardTheFetchedSecretLimit() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = emptyList()
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val model = table.model as DefaultTableModel
            val toolbar = UIUtil.findComponentOfType(panel, com.intellij.ui.CommonActionsPanel::class.java)!!
            val add = toolbar.getAnAction(com.intellij.ui.CommonActionsPanel.Buttons.ADD)!!
            val remove = toolbar.getAnAction(com.intellij.ui.CommonActionsPanel.Buttons.REMOVE)!!
            val event =
                com.intellij.testFramework.TestActionEvent.createTestEvent(
                    add,
                    com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                )

            repeat(5) { add.actionPerformed(event) }
            assertEquals("No rows can be added before the secret count is known", 0, model.rowCount)

            page.updateKnownNames(setOf("FIRST", "SECOND", "THIRD"))
            repeat(5) { add.actionPerformed(event) }
            assertEquals("Repeated Add clicks must not exceed the secret count", 3, model.rowCount)
            assertTrue((0 until model.rowCount).all { model.getValueAt(it, 0) == "" })

            table.setRowSelectionInterval(0, 0)
            remove.actionPerformed(
                com.intellij.testFramework.TestActionEvent.createTestEvent(
                    remove,
                    com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                ),
            )
            assertEquals(2, model.rowCount)
            repeat(5) { add.actionPerformed(event) }
            assertEquals("Removing a row allows only one replacement row", 3, model.rowCount)

            page.updateKnownNames(setOf("FIRST"))
            add.actionPerformed(event)
            assertEquals("A reduced secret count must not allow more rows or discard existing rows", 3, model.rowCount)

            table.cellEditor?.cancelCellEditing()
            model.rowCount = 0
            page.updateKnownNames(emptySet())
            add.actionPerformed(event)
            assertEquals("An empty config cannot have new mapping rows", 0, model.rowCount)
        } finally {
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }

    fun testMappedSecretsAreExcludedFromOtherRowsAndRestoredAfterRemoval() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("USED_SECRET", "RENAMED"), SecretNameMapping("OTHER_SECRET", "OTHER_NAME"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val model = table.model as DefaultTableModel
            val knownNames = DopplerInjectionConfigurable::class.java.getDeclaredField("knownNames").apply { isAccessible = true }
            knownNames.set(page, setOf("USED_SECRET", "OTHER_SECRET", "AVAILABLE_SECRET"))
            val cellEditor = table.columnModel.getColumn(0).cellEditor as javax.swing.DefaultCellEditor
            val combo = cellEditor.component as JComboBox<*>

            assertTrue(table.editCellAt(0, 0))
            assertEquals(listOf("AVAILABLE_SECRET"), (0 until combo.itemCount).map { combo.getItemAt(it) })
            assertEquals("USED_SECRET", combo.selectedItem)
            assertFalse(panel.isModified())
            table.cellEditor.stopCellEditing()
            assertEquals("USED_SECRET", model.getValueAt(0, 0))
            assertFalse(panel.isModified())

            model.addRow(arrayOf("", ""))
            assertTrue(table.editCellAt(2, 0))
            assertEquals(listOf("AVAILABLE_SECRET"), (0 until combo.itemCount).map { combo.getItemAt(it) })
            combo.selectedItem = "AVAILABLE_SECRET"
            table.cellEditor?.stopCellEditing()
            assertEquals("AVAILABLE_SECRET", model.getValueAt(2, 0))

            for (row in 0 until model.rowCount) {
                assertFalse("Fully mapped source names must not open an empty dropdown", table.editCellAt(row, 0))
                assertFalse(table.isEditing)
                assertTrue("Destination names remain editable", table.editCellAt(row, 1))
                table.cellEditor.cancelCellEditing()
            }
            assertEquals(
                listOf("USED_SECRET", "OTHER_SECRET", "AVAILABLE_SECRET"),
                (0 until model.rowCount).map { model.getValueAt(it, 0) },
            )

            model.removeRow(0)
            assertTrue(table.editCellAt(0, 0))
            assertEquals(listOf("USED_SECRET"), (0 until combo.itemCount).map { combo.getItemAt(it) })
            assertEquals("OTHER_SECRET", combo.selectedItem)
            table.cellEditor.cancelCellEditing()

            model.setValueAt("USED_SECRET", 0, 0)
            assertTrue(table.editCellAt(1, 0))
            assertEquals(listOf("OTHER_SECRET"), (0 until combo.itemCount).map { combo.getItemAt(it) })
            assertEquals("AVAILABLE_SECRET", combo.selectedItem)
            table.cellEditor.cancelCellEditing()

            page.updateKnownNames(emptySet())
            assertFalse("An empty remote config must not open an empty dropdown", table.editCellAt(0, 0))
        } finally {
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }

    fun testNewSecretNameOpensWithOneClickAndAVisibleCaret() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("CURRENT_SECRET", "OLD_NAME"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val click =
                java.awt.event.MouseEvent(
                    table,
                    java.awt.event.MouseEvent.MOUSE_PRESSED,
                    0,
                    0,
                    1,
                    1,
                    1,
                    false,
                )
            assertTrue("A single click should start editing", table.editCellAt(0, 1, click))
            val input = table.editorComponent as? JBTextField
            assertNotNull("New secret name should open a text field", input)
            assertTrue("The text field should show its caret", input!!.caret.isVisible)
            input.text = "RENAMED_NAME"
            assertTrue("Typing a new name should immediately mark settings modified", panel.isModified())
            assertTrue(table.isEditing)
            table.cellEditor.stopCellEditing()
            assertEquals("RENAMED_NAME", table.model.getValueAt(0, 1))
            assertTrue(table.editCellAt(0, 1))
            input.text = ""
            assertTrue("An empty new name should still show its caret", input.caret.isVisible)
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }

    fun testRepeatedDirtyChecksDoNotFireEditorOrTableEvents() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("CURRENT_SECRET", "NEW_NAME"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            for (column in 0..1) {
                assertTrue(table.editCellAt(0, column))
                var actionEvents = 0
                var modelEvents = 0
                val editor = table.editorComponent
                if (editor is JComboBox<*>) editor.addActionListener { actionEvents++ }
                if (editor is JTextField) editor.addActionListener { actionEvents++ }
                val listener = javax.swing.event.TableModelListener { modelEvents++ }
                table.model.addTableModelListener(listener)
                try {
                    repeat(100) { panel.isModified() }
                    assertTrue("Dirty checks must leave the editor open", table.isEditing)
                    assertEquals("Dirty checks must not dispatch editor actions", 0, actionEvents)
                    assertEquals("Dirty checks must not mutate the table", 0, modelEvents)
                } finally {
                    table.model.removeTableModelListener(listener)
                    table.cellEditor?.cancelCellEditing()
                }
            }
        } finally {
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }

    fun testSuggestionRefreshIsConsumedBeforeComboCallbacksCanRequeueIt() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("OLD_SECRET", "NEW_NAME"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            val cellEditor = table.columnModel.getColumn(0).cellEditor as javax.swing.DefaultCellEditor
            val combo = cellEditor.component as JComboBox<*>
            val pending = DopplerInjectionConfigurable::class.java.getDeclaredField("pendingNames").apply { isAccessible = true }
            var events = 0
            var refreshStillPending = false
            combo.addActionListener {
                events++
                if (pending.get(page) != null) refreshStillPending = true
            }
            val refresh =
                DopplerInjectionConfigurable::class.java
                    .getDeclaredMethod("refreshNameSuggestions", Set::class.java)
                    .apply { isAccessible = true }

            refresh.invoke(page, setOf("CURRENT_SECRET"))

            assertTrue("The test must exercise combo model callbacks", events > 0)
            assertFalse("Combo callbacks must not see a pending refresh they can requeue", refreshStillPending)
            assertNull(pending.get(page))
            assertEquals("OLD_SECRET", table.model.getValueAt(0, 0))
        } finally {
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }

    fun testRemainingMappingKeepsTableColorsAfterRemovingARow() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        val previousFocusBackground = javax.swing.UIManager.get("Table.focusCellBackground")
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("FIRST", "RENAMED_FIRST"), SecretNameMapping("SECOND", "RENAMED_SECOND"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            page.updateKnownNames(setOf("FIRST", "SECOND"))
            val table = UIUtil.findComponentOfType(panel, JTable::class.java)!!
            table.background = java.awt.Color(40, 40, 40)
            table.foreground = java.awt.Color.LIGHT_GRAY
            table.selectionBackground = java.awt.Color(45, 70, 110)
            table.selectionForeground = java.awt.Color.WHITE
            javax.swing.UIManager.put("Table.focusCellBackground", java.awt.Color.WHITE)
            val model = table.model as DefaultTableModel
            model.removeRow(0)
            assertEquals("SECOND", model.getValueAt(0, 0))

            val renderer = table.columnModel.getColumn(0).cellRenderer
            for (selected in listOf(false, true)) {
                for (focused in listOf(false, true)) {
                    val cell = renderer.getTableCellRendererComponent(table, "SECOND", selected, focused, 0, 0)
                    assertEquals(
                        "A remaining mapping must use table colors, not Swing's focus background",
                        if (selected) table.selectionBackground else table.background,
                        cell.background,
                    )
                    assertEquals(if (selected) table.selectionForeground else table.foreground, cell.foreground)
                }
            }
        } finally {
            javax.swing.UIManager.put("Table.focusCellBackground", previousFocusBackground)
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }

    fun testReopeningMarksARenamedRemoteSecretAsABrokenMapping() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy(customMappings = settings.customMappings.map { it.copy() }.toMutableList())
        settings.nameTransformer = DopplerNameTransformer.CUSTOM
        settings.customMappings = listOf(SecretNameMapping("ORIGINAL_SECRET", "INJECTED_NAME"))
        val page = DopplerInjectionConfigurable(project)
        try {
            val initialPanel = page.createPanel()
            initialPanel.reset()
            page.updateKnownNames(setOf("ORIGINAL_SECRET"))
            page.disposeUIResources()

            val reopenedPanel = page.createPanel()
            reopenedPanel.reset()
            val table = UIUtil.findComponentOfType(reopenedPanel, JTable::class.java)!!
            val renderer = table.columnModel.getColumn(0).cellRenderer
            val loading = renderer.getTableCellRendererComponent(table, "ORIGINAL_SECRET", false, false, 0, 0) as javax.swing.JLabel
            assertNull("Unknown names must not be marked broken while loading", loading.icon)

            page.updateKnownNames(setOf("RENAMED_SECRET"))
            for (selected in listOf(false, true)) {
                val broken = renderer.getTableCellRendererComponent(table, "ORIGINAL_SECRET", selected, false, 0, 0) as javax.swing.JLabel
                assertNotNull("Broken mapping must remain visible even when selected", broken.icon)
                assertTrue(broken.toolTipText.contains("Broken mapping"))
                assertEquals(if (selected) table.selectionForeground else com.intellij.ui.JBColor.RED, broken.foreground)

                val existing = renderer.getTableCellRendererComponent(table, "RENAMED_SECRET", selected, false, 0, 0) as javax.swing.JLabel
                assertNull(existing.icon)
                assertNull(existing.toolTipText)
                assertEquals(
                    "Existing secrets must not inherit a previous row's error color",
                    if (selected) table.selectionForeground else table.foreground,
                    existing.foreground,
                )
            }
            assertEquals("ORIGINAL_SECRET", table.model.getValueAt(0, 0))
            assertEquals("INJECTED_NAME", settings.customMappings.single().newName)
            assertFalse(reopenedPanel.isModified())

            table.model.setValueAt("RENAMED_SECRET", 0, 0)
            val repaired = renderer.getTableCellRendererComponent(table, "RENAMED_SECRET", false, false, 0, 0) as javax.swing.JLabel
            assertNull(repaired.icon)
            assertNull(repaired.toolTipText)
            assertEquals(table.foreground, repaired.foreground)
        } finally {
            page.disposeUIResources()
            settings.loadState(saved)
        }
    }
}
