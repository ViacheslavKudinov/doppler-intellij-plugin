package com.vk.doppler.editor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck

class DopplerFileCloseCheck : VirtualFilePreCloseCheck {
    override fun canCloseFile(file: VirtualFile): Boolean {
        val config = file as? DopplerSecretsFile ?: return true
        if (!hasChanges(config)) return true
        val project =
            ProjectManager.getInstance().openProjects.firstOrNull {
                !it.isDisposed && FileEditorManagerEx.getInstanceEx(it).isFileOpen(config)
            } ?: return false
        return confirmClose(config, project) {
            Messages.showDialog(
                project,
                "Save changes to /${config.projectSlug}/${config.configName} before closing?",
                "Unsaved Doppler Changes",
                arrayOf("Save", "Don't Save", "Cancel"),
                0,
                Messages.getWarningIcon(),
            )
        }
    }

    internal fun confirmClose(
        file: DopplerSecretsFile,
        project: Project,
        choose: () -> Int,
    ): Boolean {
        if (!hasChanges(file)) return true
        val service = DopplerSecretsEditorService.getInstance(project)
        service.cancelCloseRequest(file)
        return when (choose()) {
            0 -> {
                val manager = FileEditorManagerEx.getInstanceEx(project)
                val window = manager.currentWindow?.takeIf { it.isFileOpen(file) }
                service.saveBeforeClose(file) {
                    if (!project.isDisposed) {
                        if (window != null && !window.isDisposed) {
                            manager.closeFile(file, window)
                        } else {
                            manager.closeFile(file)
                        }
                    }
                }
            }

            1 -> {
                if (file.pushInProgress.get()) {
                    Messages.showWarningDialog(
                        project,
                        "Wait for the current Doppler sync before discarding changes.",
                        "Doppler Sync in Progress",
                    )
                    false
                } else {
                    service.discardLocalChanges(file)
                    true
                }
            }

            else -> {
                false
            }
        }
    }

    private fun hasChanges(file: DopplerSecretsFile): Boolean {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)
        return file.hasPendingSync || file.pushInProgress.get() ||
            (document != null && documents.isDocumentUnsaved(document))
    }
}
