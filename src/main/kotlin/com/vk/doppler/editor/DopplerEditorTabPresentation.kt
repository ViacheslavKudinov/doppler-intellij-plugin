package com.vk.doppler.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tabs.TabInfo
import java.beans.PropertyChangeListener

internal class DopplerEditorTabPresentation(
    private val project: Project,
) : Disposable {
    private val listeners = mutableMapOf<TabInfo, PropertyChangeListener>()
    private var updating = false
    private val documentListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) as? DopplerSecretsFile ?: return
                if (project.isDisposed || file.isInvalidated) return
                listeners.keys
                    .toList()
                    .filter { fileFor(it) === file }
                    .forEach(::update)
            }
        }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
    }

    fun refresh() {
        if (project.isDisposed || updating) return
        val tabs = editorTabs()
        listeners.keys.toList().filter { it !in tabs }.forEach { tab ->
            tab.changeSupport.removePropertyChangeListener(listeners.remove(tab))
        }
        tabs.forEach(::update)
    }

    fun update(tab: TabInfo) {
        if (project.isDisposed || updating) return
        val file = fileFor(tab) ?: return
        if (tab !in listeners) {
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == TabInfo.TEXT) update(tab)
                }
            listeners[tab] = listener
            tab.changeSupport.addPropertyChangeListener(listener)
        }
        val title = DopplerEditorTabTitleProvider().getEditorTabTitle(project, file) ?: return
        val fragments =
            if (title == file.configName) {
                listOf(file.configName)
            } else {
                listOf(file.configName, title.removePrefix(file.configName))
            }
        val attributes =
            listOf(
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, tab.defaultForeground),
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, tab.defaultForeground),
            ).take(fragments.size)
        if (tab.coloredText.texts == fragments && tab.coloredText.attributes == attributes) return
        updating = true
        try {
            tab.clearText(false)
            fragments.zip(attributes).forEach { (fragment, style) -> tab.append(fragment, style) }
        } finally {
            updating = false
        }
    }

    override fun dispose() {
        listeners.forEach { (tab, listener) -> tab.changeSupport.removePropertyChangeListener(listener) }
        listeners.clear()
    }

    private fun editorTabs(): List<TabInfo> =
        FileEditorManagerEx
            .getInstanceEx(project)
            .windows
            .flatMap { it.tabbedPane.tabs.tabs }
            .filter { fileFor(it) != null }

    private fun fileFor(tab: TabInfo): DopplerSecretsFile? =
        when (val tabObject = tab.`object`) {
            is DopplerSecretsFile -> {
                tabObject
            }

            is FileEditor -> {
                FileEditorManager.getInstance(project).let { manager ->
                    manager.openFiles
                        .asSequence()
                        .filterIsInstance<DopplerSecretsFile>()
                        .firstOrNull { file -> manager.getEditors(file).any { it === tabObject } }
                }
            }

            else -> {
                null
            }
        }
}
