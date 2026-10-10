package com.vk.doppler.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shows the bare config name on the tab. The file itself keeps its `.yaml` name so the IDE
 * still resolves YAML highlighting from the extension.
 */
class DopplerEditorTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? DopplerSecretsFile)?.configName
}
