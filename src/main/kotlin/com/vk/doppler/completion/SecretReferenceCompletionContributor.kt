package com.vk.doppler.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.vk.doppler.editor.DopplerSecretsFile

/** Completes `${...}` references inside the Doppler secrets editor. */
class DopplerSecretReferenceCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), SecretReferenceProvider)
    }
}

private object SecretReferenceProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile.virtualFile as? DopplerSecretsFile ?: return
        val editor = parameters.editor
        val project = editor.project ?: return
        val text = editor.document.charsSequence
        val reference = SecretReferenceParser.contextAt(text, parameters.offset) ?: return

        val index = SecretReferenceService.getInstance().namesFor(file.authContextId) {
            SecretReferencePopup.scheduleLater(project, editor)
        }
        val candidates = SecretReferenceCandidates.find(
            SecretReferenceLocation(file.projectSlug, file.configName),
            SecretReferenceParser.secretNames(text),
            index,
            reference.prefix,
        )

        val matcher = result.withPrefixMatcher(reference.prefix)
        for (candidate in candidates) {
            matcher.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(candidate.reference)
                        .withTypeText(candidate.origin, true)
                        .withInsertHandler(CloseReferenceInsertHandler),
                    candidate.scope.priority,
                ),
            )
        }
    }
}

/** Finishes a completed reference with its closing brace and moves the caret past it. */
private object CloseReferenceInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val tail = context.tailOffset
        val closing = SecretReferenceParser.closingAfter(document.charsSequence, tail)
        if (closing.removeUntil > tail) document.deleteString(tail, closing.removeUntil)
        if (closing.insertBrace) document.insertString(tail, "}")
        context.editor.caretModel.moveToOffset(closing.caretOffset)
    }
}

internal object SecretReferencePopup {

    /** Opens the completion popup if the caret is still inside an unclosed reference. */
    fun schedule(project: Project, editor: Editor) {
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, Condition { file ->
            file.virtualFile is DopplerSecretsFile &&
                SecretReferenceParser.contextAt(editor.document.charsSequence, editor.caretModel.offset) != null
        })
    }

    fun scheduleLater(project: Project, editor: Editor) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed && !editor.isDisposed) schedule(project, editor)
            },
            ModalityState.defaultModalityState(),
        )
    }
}
