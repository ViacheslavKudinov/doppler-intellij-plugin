package com.vk.doppler.editor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Disambiguates shared config names without changing the file's YAML highlighting.
 */
class DopplerEditorTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(
        project: Project,
        file: VirtualFile,
    ): String? {
        val config = file as? DopplerSecretsFile ?: return null
        val duplicate =
            FileEditorManager.getInstance(project).openFiles.any {
                it is DopplerSecretsFile && it != config && it.configName == config.configName
            }
        val title =
            if (duplicate) {
                "${config.configName} ${config.displayPath}"
            } else {
                config.configName
            }
        val document = FileDocumentManager.getInstance().getCachedDocument(config)
        val serverText = SecretsDocumentFormat.serialize(config.remoteSnapshot)
        val editing =
            config.pushInProgress.get() ||
                (document?.text?.let { it != serverText } ?: config.hasPendingSync)
        return if (editing) "$title *" else title
    }
}
