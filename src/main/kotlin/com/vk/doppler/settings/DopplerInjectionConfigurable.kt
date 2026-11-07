package com.vk.doppler.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.cli.DopplerNameTransformer
import com.vk.doppler.injection.SecretNameMapping
import com.vk.doppler.injection.validateSecretNameMappings
import com.vk.doppler.service.DopplerService
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.util.EventObject
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.Timer
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

internal fun refreshSecretNameSuggestions(
    model: DefaultComboBoxModel<String>,
    knownNames: Set<String>,
) {
    val suggestions = knownNames.filter { it.isNotBlank() }.sorted()
    val selectedName = model.selectedItem as? String
    model.removeAllElements()
    suggestions.forEach(model::addElement)
    model.selectedItem = selectedName?.takeIf { it in suggestions }
}

class DopplerInjectionConfigurable(
    private val project: Project,
) : BoundConfigurable("Run and Debug") {
    private var uiDisposable: Disposable? = null
    private var setupAvailable = false
    private var displayedSetup: DopplerFolderSetup? = null
    private var namesRequest = 0
    private var updateAvailability: ((Boolean) -> Unit)? = null
    internal val defaultsMessage = JLabel()

    /** Reads defaults and optional names in the same project scope; tests supply synthetic data. */
    internal var defaultConfigLoader: () -> Pair<DopplerFolderSetup, Set<String>?> = ::readDefaultConfig

    private var transformerBox: ComboBox<DopplerNameTransformer>? = null
    private var mappingTable: JBTable? = null
    private var mappingModel: DefaultTableModel? = null
    private var nameEditorModel: DefaultComboBoxModel<String>? = null
    internal val validationMessage =
        JLabel().apply {
            foreground = JBColor.RED
            icon = AllIcons.General.Error
            isVisible = false
        }
    internal val validationMessageTimer = Timer(5_000) { clearValidationMessage() }.apply { isRepeats = false }

    /** Secret names of the default config; null while unknown, so nothing is marked missing. */
    @Volatile private var knownNames: Set<String>? = null

    @Volatile private var closed = false

    @Volatile private var pendingNames: Set<String>? = null

    override fun createPanel(): DialogPanel {
        uiDisposable?.let(Disposer::dispose)
        val lifetime = Disposer.newDisposable("Doppler Run and Debug settings")
        uiDisposable = lifetime
        closed = false
        displayedSetup = null
        setupAvailable = false
        knownNames = null
        pendingNames = null
        val settings = DopplerInjectionSettings.getInstance(project)
        val model = DefaultTableModel(arrayOf("Current secret name", "New secret name"), 0)
        val nameEditorModel = DefaultComboBoxModel<String>()

        val table = createMappingTable(model)
        this.mappingModel = model
        this.mappingTable = table
        this.nameEditorModel = nameEditorModel
        val mappingRenderer = MappingCellRenderer()
        val nameCombo = ComboBox(nameEditorModel).apply { isEditable = false }
        nameCombo.addActionListener { clearValidationMessage() }
        model.addTableModelListener { clearValidationMessage() }
        val nameCellEditor =
            object : DefaultCellEditor(nameCombo) {
                private var originalName: Any? = null

                override fun getTableCellEditorComponent(
                    table: JTable,
                    value: Any?,
                    isSelected: Boolean,
                    row: Int,
                    column: Int,
                ): Component {
                    originalName = value
                    val names = knownNames ?: (0 until nameEditorModel.size).map { nameEditorModel.getElementAt(it) }.toSet()
                    val modelRow = table.convertRowIndexToModel(row)
                    val usedNames =
                        (0 until model.rowCount)
                            .filter { it != modelRow }
                            .map { (model.getValueAt(it, 0) as? String).orEmpty().trim() }
                            .toSet()
                    refreshSecretNameSuggestions(nameEditorModel, names - usedNames - (value as? String).orEmpty().trim())
                    val component = super.getTableCellEditorComponent(table, value, isSelected, row, column)
                    nameEditorModel.selectedItem = value?.takeIf { it in names && it !in usedNames }
                    return component
                }

                override fun getCellEditorValue(): Any? = super.getCellEditorValue() ?: originalName
            }
        table.columnModel.getColumn(0).apply {
            cellRenderer = mappingRenderer
            cellEditor = nameCellEditor
        }
        table.columnModel.getColumn(1).cellRenderer = mappingRenderer
        val newNameField = JBTextField()
        newNameField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = clearValidationMessage()
            },
        )
        table.columnModel.getColumn(1).cellEditor = DefaultCellEditor(newNameField).apply { clickCountToStart = 1 }
        nameCellEditor.addCellEditorListener(
            object : CellEditorListener {
                override fun editingStopped(event: ChangeEvent) = refreshPendingNames()

                override fun editingCanceled(event: ChangeEvent) = refreshPendingNames()
            },
        )

        val decorated =
            ToolbarDecorator
                .createDecorator(table)
                .setToolbarPosition(ActionToolbarPosition.TOP)
                .disableUpDownActions()
                .setAddActionUpdater { canAddMapping(model) }
                .setAddAction {
                    addMapping(table, model)
                }.setRemoveActionUpdater { setupAvailable && table.selectedRowCount > 0 }
                .setRemoveAction {
                    if (!setupAvailable) return@setRemoveAction
                    table.cellEditor?.stopCellEditing()
                    table.selectedRows.sortedDescending().forEach(model::removeRow)
                }.createPanel()
                .apply { preferredSize = Dimension(420, 320) }

        lateinit var mappingRow: Row
        lateinit var injectionRow: Row
        lateinit var transformerRow: Row
        val dialog =
            panel {
                row { cell(defaultsMessage) }
                injectionRow =
                    row {
                        checkBox("Automatically inject Doppler secrets into Run and Debug")
                            .bindSelected(settings::enabled)
                            .comment(
                                "Uses this project's default Doppler config from Folder Setup. Supports Java, Python, Node.js, Maven, Gradle, and Docker.",
                            )
                    }
                transformerRow =
                    row("Name transformation:") {
                        comboBox(DopplerNameTransformer.entries)
                            .bindItem({ settings.nameTransformer }, { settings.nameTransformer = it ?: DopplerNameTransformer.NONE })
                            .comment("None keeps the original names. Applies only to injected Doppler secrets; values are unchanged.")
                            .applyToComponent {
                                transformerBox = this
                                addActionListener {
                                    clearValidationMessage()
                                    mappingRow.visible(selectedItem == DopplerNameTransformer.CUSTOM)
                                }
                            }
                    }
                mappingRow =
                    row {
                        cell(
                            UiDataProvider.wrapComponent(decorated) { sink ->
                                sink[LangDataKeys.NO_NEW_ACTION] = true
                            },
                        ).align(AlignX.FILL)
                            .comment(
                                "Secrets with these names are injected under the new name; others keep theirs. " +
                                    "Names in red are not in the default config: they are kept but not injected. " +
                                    "Pairs are saved while another transformation is selected and used only with Custom.",
                            ).onReset {
                                clearValidationMessage()
                                model.rowCount = 0
                                settings.customMappings.forEach { model.addRow(arrayOf(it.name, it.newName)) }
                                mappingRow.visible(transformerBox?.selectedItem == DopplerNameTransformer.CUSTOM)
                            }.onIsModified { currentMappings() != settings.customMappings }
                            .onApply { settings.customMappings = currentMappings() }
                    }
                row { cell(validationMessage) }
                row {
                    comment(
                        "Every launch fetches fresh secrets without caching them. Existing run configuration variables take precedence. " +
                            "Launched processes keep their inherited environment for their lifetime.",
                    )
                }
            }
        updateAvailability = { available ->
            if (!available) table.cellEditor?.stopCellEditing()
            injectionRow.enabled(available)
            transformerRow.enabled(available)
            mappingRow.enabled(available)
            UIUtil.setEnabled(decorated, available, true)
            table.isEnabled = available
        }
        val folderSetup = DopplerFolderSetupState.getInstance(project)
        updateDefaultAvailability(folderSetup.current)
        project.messageBus.connect(lifetime).subscribe(
            DopplerFolderSetupState.TOPIC,
            DopplerFolderSetupListener {
                if (displayedSetup != folderSetup.current) {
                    updateDefaultAvailability(folderSetup.current)
                    loadKnownNames()
                }
            },
        )
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = setupAvailable && transformerBox?.selectedItem == DopplerNameTransformer.CUSTOM
            }

            override fun actionPerformed(event: AnActionEvent) {
                if (setupAvailable && transformerBox?.selectedItem == DopplerNameTransformer.CUSTOM) addMapping(table, model)
            }
        }.registerCustomShortcutSet(CommonShortcuts.getNewForDialogs(), dialog)
        loadKnownNames()
        return dialog
    }

    private fun hasUnmappedSecrets(model: DefaultTableModel): Boolean {
        val names = knownNames ?: return true
        val mappedNames =
            (0 until model.rowCount)
                .map { (model.getValueAt(it, 0) as? String).orEmpty().trim() }
                .toSet()
        return names.any { it !in mappedNames }
    }

    private fun canAddMapping(model: DefaultTableModel): Boolean {
        if (!setupAvailable) return false
        val names = knownNames ?: return false
        return model.rowCount < names.size && hasUnmappedSecrets(model)
    }

    private fun addMapping(
        table: JBTable,
        model: DefaultTableModel,
    ) {
        if (!canAddMapping(model)) return
        table.cellEditor?.stopCellEditing()
        model.addRow(arrayOf("", ""))
        editMappingRow(table, model.rowCount - 1)
    }

    private fun createMappingTable(model: DefaultTableModel): JBTable {
        val table =
            object : JBTable(model) {
                override fun isCellEditable(
                    row: Int,
                    column: Int,
                ): Boolean {
                    if (!setupAvailable) return false
                    if (convertColumnIndexToModel(column) == 0 && !hasUnmappedSecrets(model)) return false
                    return super.isCellEditable(row, column)
                }

                override fun editCellAt(
                    row: Int,
                    column: Int,
                    event: EventObject?,
                ): Boolean {
                    val editing = super.editCellAt(row, column, event)
                    if (editing) {
                        (editorComponent as? JBTextField)?.let { input ->
                            input.requestFocusInWindow()
                            input.caret.isVisible = true
                        }
                    }
                    return editing
                }
            }
        val headerRenderer = table.tableHeader.defaultRenderer
        table.tableHeader.defaultRenderer =
            javax.swing.table.TableCellRenderer { headerTable, value, selected, focused, row, column ->
                headerRenderer.getTableCellRendererComponent(headerTable, value, selected, focused, row, column).apply {
                    font = table.tableHeader.font.deriveFont(Font.BOLD)
                    background = UIUtil.getPanelBackground()
                    if (this is JComponent) {
                        isOpaque = true
                        border =
                            BorderFactory.createCompoundBorder(
                                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                                JBUI.Borders.empty(4, 8),
                            )
                    }
                }
            }
        return table
    }

    private fun editMappingRow(
        table: JTable,
        row: Int,
    ) {
        table.setRowSelectionInterval(row, row)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
        table.requestFocusInWindow()
        if (table.editCellAt(row, 0)) {
            table.editorComponent?.requestFocusInWindow()
        }
    }

    override fun isModified(): Boolean = setupAvailable && super.isModified()

    override fun apply() {
        if (!setupAvailable) return
        mappingTable?.cellEditor?.stopCellEditing()
        clearValidationMessage()
        if (transformerBox?.selectedItem == DopplerNameTransformer.CUSTOM) {
            validateSecretNameMappings(currentMappings())?.let {
                validationMessage.text = it
                validationMessage.isVisible = true
                validationMessageTimer.restart()
                throw ProcessCanceledException()
            }
        }
        super.apply()
    }

    private fun clearValidationMessage() {
        validationMessageTimer.stop()
        validationMessage.text = ""
        validationMessage.isVisible = false
    }

    override fun disposeUIResources() {
        closed = true
        namesRequest++
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
        updateAvailability = null
        clearValidationMessage()
        transformerBox = null
        mappingTable = null
        mappingModel = null
        nameEditorModel = null
        super.disposeUIResources()
    }

    /** Pairs from the table; fully blank rows are dropped. */
    private fun currentMappings(): List<SecretNameMapping> {
        val model = mappingModel ?: return emptyList()
        val mappings =
            (0 until model.rowCount)
                .map {
                    SecretNameMapping(
                        (model.getValueAt(it, 0) as? String).orEmpty().trim(),
                        (model.getValueAt(it, 1) as? String).orEmpty(),
                    )
                }.toMutableList()
        val table = mappingTable
        if (table?.isEditing == true && table.editingRow in mappings.indices) {
            val value = table.cellEditor?.cellEditorValue as? String
            if (value != null) {
                if (table.editingColumn == 0) mappings[table.editingRow].name = value.trim()
                if (table.editingColumn == 1) mappings[table.editingRow].newName = value
            }
        }
        return mappings.filter { it.name.isNotBlank() || it.newName.isNotBlank() }
    }

    private fun updateDefaultAvailability(setup: DopplerFolderSetup) {
        if (displayedSetup != setup) {
            knownNames = null
            pendingNames = null
            nameEditorModel?.removeAllElements()
            clearValidationMessage()
        }
        displayedSetup = setup
        setupAvailable = !setup.projectSlug.isNullOrBlank() && !setup.configName.isNullOrBlank()
        defaultsMessage.text = "Choose a default Doppler project and config in Folder Setup to configure Run and Debug injection."
        defaultsMessage.isVisible = !setupAvailable
        updateAvailability?.invoke(setupAvailable)
        mappingTable?.repaint()
    }

    private fun readDefaultConfig(): Pair<DopplerFolderSetup, Set<String>?> {
        val folder = project.basePath ?: return DopplerFolderSetup() to null
        val service = DopplerService.getInstance(project)
        val setup = service.folderSetup(folder, CliOperation.FOLDER_SETUP)
        val slug = setup.projectSlug
        val config = setup.configName
        val names =
            if (slug.isNullOrBlank() || config.isNullOrBlank()) {
                null
            } else {
                try {
                    service.listSecretNames(slug, config)
                } catch (_: DopplerCliException) {
                    null
                }
            }
        return setup to names
    }

    private fun loadKnownNames() {
        val request = ++namesRequest
        val table = mappingTable
        ApplicationManager.getApplication().executeOnPooledThread {
            val result =
                try {
                    defaultConfigLoader()
                } catch (_: DopplerCliException) {
                    null
                }
            ApplicationManager.getApplication().invokeLater({
                if (closed || project.isDisposed || mappingTable !== table || request != namesRequest) return@invokeLater
                updateDefaultAvailability(result?.first ?: DopplerFolderSetup())
                if (result == null) {
                    defaultsMessage.text = "Could not read Folder Setup. Check the CLI login and reload this Settings page."
                } else {
                    DopplerFolderSetupState.getInstance(project).update(result.first)
                    result.second?.let(::updateKnownNames)
                }
            }, ModalityState.any())
        }
    }

    internal fun updateKnownNames(names: Set<String>) {
        knownNames = names
        refreshNameSuggestions(names)
        mappingTable?.repaint()
    }

    private fun refreshNameSuggestions(names: Set<String>) {
        pendingNames = names
        val table = mappingTable
        if (table?.isEditing == true && table.editingColumn == 0) return
        pendingNames = null
        nameEditorModel?.let { refreshSecretNameSuggestions(it, names) }
    }

    private fun refreshPendingNames() {
        val names = pendingNames ?: return
        ApplicationManager.getApplication().invokeLater({
            if (!closed) refreshNameSuggestions(names)
        }, ModalityState.any())
    }

    private inner class MappingCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val name = (value as? String).orEmpty().trim()
            val missing = table.convertColumnIndexToModel(column) == 0 && name.isNotEmpty() && knownNames?.contains(name) == false
            icon = if (missing) AllIcons.General.Error else null
            toolTipText = if (missing) "Broken mapping: this secret is missing from the default config. Select a replacement." else null
            foreground =
                when {
                    isSelected -> table.selectionForeground
                    missing -> JBColor.RED
                    else -> table.foreground
                }
            background = if (isSelected) table.selectionBackground else table.background
            border = JBUI.Borders.empty(2, 8)
            return component
        }
    }
}
