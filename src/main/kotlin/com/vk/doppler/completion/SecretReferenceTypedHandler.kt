package com.vk.doppler.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.vk.doppler.editor.DopplerSecretsFile

/** Reopens suggestions while editing a reference, and keeps whitespace out of it. */
class DopplerSecretReferenceTypedHandler internal constructor(
    private val schedulePopup: (Project, Editor) -> Unit,
) : TypedHandlerDelegate() {
    constructor() : this(SecretReferencePopup::schedule)

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

    override fun charTyped(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        // Inspect the document after insertion, including when an existing popup was dismissed.
        if (file.virtualFile is DopplerSecretsFile &&
            SecretReferenceParser.contextAt(editor.document.charsSequence, editor.caretModel.offset) != null) {
            schedulePopup(project, editor)
        }
        return Result.CONTINUE
    }
}
