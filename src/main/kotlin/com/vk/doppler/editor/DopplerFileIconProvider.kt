package com.vk.doppler.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.vk.doppler.DopplerIcons
import javax.swing.Icon

class DopplerFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file is DopplerSecretsFile) DopplerIcons.EditorTab else null
}