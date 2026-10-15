package com.vk.doppler.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.vk.doppler.editor.DopplerSecretsFile

/** Opens suggestions on `${` and `.`, and keeps whitespace out of an unfinished reference. */
class DopplerSecretReferenceTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType,
    ): Result {
        val insideReference = charTyped.isWhitespace() &&
            file.virtualFile is DopplerSecretsFile &&
            SecretReferenceParser.contextAt(editor.document.charsSequence, editor.caretModel.offset) != null
        return if (insideReference) Result.STOP else Result.CONTINUE
    }

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped == '{' || charTyped == '.') SecretReferencePopup.schedule(project, editor)
        return Result.CONTINUE
    }
}
