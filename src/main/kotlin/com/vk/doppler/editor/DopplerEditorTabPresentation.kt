package com.vk.doppler.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tabs.TabInfo
import java.beans.PropertyChangeListener

internal class DopplerEditorTabPresentation(private val project: Project) : Disposable {
    private val listeners = mutableMapOf<TabInfo, PropertyChangeListener>()
    private var updating = false

    fun refresh() {
        if (project.isDisposed || updating) return
        val tabs = FileEditorManagerEx.getInstanceEx(project).windows
            .flatMap { it.tabbedPane.tabs.tabs }
            .filter { it.`object` is DopplerSecretsFile }
        listeners.keys.toList().filter { it !in tabs }.forEach { tab ->
            tab.changeSupport.removePropertyChangeListener(listeners.remove(tab))
        }
        tabs.forEach(::update)
    }

    fun update(tab: TabInfo) {
        if (project.isDisposed || updating) return
        val file = tab.`object` as? DopplerSecretsFile ?: return
        if (tab !in listeners) {
            val listener = PropertyChangeListener { event ->
                if (event.propertyName == TabInfo.TEXT) update(tab)
            }
            listeners[tab] = listener
            tab.changeSupport.addPropertyChangeListener(listener)
        }
        val title = DopplerEditorTabTitleProvider().getEditorTabTitle(project, file) ?: return
        val fragments = if (title == file.configName) listOf(file.configName) else {
            listOf(file.configName, title.removePrefix(file.configName))
        }
        val attributes = listOf(
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
}