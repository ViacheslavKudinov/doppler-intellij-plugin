package com.vk.doppler.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Disambiguates shared config names without changing the file's YAML highlighting.
 */
class DopplerEditorTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        val config = file as? DopplerSecretsFile ?: return null
        val duplicate = FileEditorManager.getInstance(project).openFiles.any {
            it is DopplerSecretsFile && it != config && it.configName == config.configName
        }
        return if (duplicate) {
            "${config.configName} ${fullPath(config)}"
        } else {
            config.configName
        }
    }

    override fun getEditorTabTooltipText(project: Project, virtualFile: VirtualFile): String? =
        (virtualFile as? DopplerSecretsFile)?.let(::fullPath)

    private fun fullPath(file: DopplerSecretsFile): String =
        listOfNotNull(file.projectSlug, file.environment, file.configName).joinToString("/", prefix = "/")
}
