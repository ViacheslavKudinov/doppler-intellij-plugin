package com.vk.doppler.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.vk.doppler.DopplerIcons
import com.vk.doppler.settings.DopplerFolderSetupListener
import com.vk.doppler.settings.DopplerFolderSetupState
import javax.swing.Icon

class DopplerFileIconProvider : FileIconProvider {
    override fun getIcon(
        file: VirtualFile,
        flags: Int,
        project: Project?,
    ): Icon? {
        if (file !is DopplerSecretsFile) return null
        val bookmarked =
            project != null &&
                DopplerFolderSetupState
                    .getInstance(project)
                    .current
                    .matchesConfig(file.projectSlug, file.configName)
        return if (bookmarked) {
            DopplerIcons.BookmarkedEditorTab
        } else {
            DopplerIcons.EditorTab
        }
    }
}

internal class DopplerFolderSetupTabIconUpdater(
    private val openFiles: () -> Array<VirtualFile>,
    private val updatePresentation: (VirtualFile) -> Unit,
) : DopplerFolderSetupListener {
    override fun changed() {
        openFiles().filterIsInstance<DopplerSecretsFile>().forEach(updatePresentation)
    }
}
