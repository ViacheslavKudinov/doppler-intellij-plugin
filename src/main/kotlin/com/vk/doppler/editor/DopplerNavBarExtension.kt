package com.vk.doppler.editor

import com.intellij.ide.navigationToolbar.AbstractNavBarModelExtension
import com.intellij.psi.PsiFile
import com.vk.doppler.settings.DopplerFolderSetupState
import javax.swing.Icon

/** Distinguishes same-named configs in navigation-bar presentations, including bookmark changes. */
class DopplerNavBarExtension : AbstractNavBarModelExtension() {
    override fun getPresentableText(obj: Any): String? = getPresentableText(obj, false)

    override fun getPresentableText(
        obj: Any,
        forPopup: Boolean,
    ): String? {
        val file = configFile(obj) ?: return null
        if (!forPopup) return file.configName
        val project = (obj as? PsiFile)?.project
        val bookmarked =
            project != null && DopplerFolderSetupState.getInstance(project).current.matchesConfig(file.projectSlug, file.configName)
        return if (bookmarked) "${file.displayPath} (default config)" else file.displayPath
    }

    override fun getIcon(obj: Any): Icon? {
        val file = configFile(obj) ?: return null
        return DopplerFileIconProvider().getIcon(file, 0, (obj as? PsiFile)?.project)
    }

    private fun configFile(obj: Any): DopplerSecretsFile? = (obj as? PsiFile)?.virtualFile as? DopplerSecretsFile
}
