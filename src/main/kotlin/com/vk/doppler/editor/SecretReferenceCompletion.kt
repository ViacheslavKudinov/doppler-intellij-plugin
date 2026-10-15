package com.vk.doppler.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext

internal data class SecretReferenceSource(
    val projectSlug: String,
    val environment: String?,
    val configName: String,
    val secretNames: Set<String>,
)

internal data class SecretReferenceTarget(
    val projectSlug: String,
    val environment: String?,
    val configName: String,
)

internal data class SecretReferenceContext(
    val prefix: String,
)

internal object SecretReferenceCompletion {
    fun contextAt(text: CharSequence, caretOffset: Int): SecretReferenceContext? {
        if (caretOffset !in 0..text.length) return null
        val lineStart = (caretOffset - 1 downTo 0).firstOrNull { text[it] == '\n' }?.plus(1) ?: 0
        val linePrefix = text.subSequence(lineStart, caretOffset)
        if (linePrefix.toString().trimStart().startsWith("#")) return null

        val start = text.lastIndexOf("\${", caretOffset - 1)
        if (start < lineStart || start < 0 || text.subSequence(start + 2, caretOffset).contains('}')) return null
        val prefix = text.subSequence(start + 2, caretOffset).toString()
        if (prefix.any { it.isWhitespace() || it == '{' || it == '}' }) return null
        return SecretReferenceContext(prefix)
    }

    fun candidates(
        current: SecretReferenceSource,
        sources: Iterable<SecretReferenceSource>,
        prefix: String,
    ): List<String> = sources.asSequence()
        .flatMap { source ->
            source.secretNames.asSequence().mapNotNull { secret ->
                val reference = when {
                    source.projectSlug != current.projectSlug ->
                        source.environment?.let { "${source.projectSlug}.$it.${source.configName}.$secret" }
                    source.configName == current.configName -> secret
                    source.environment != null && source.environment == current.environment ->
                        "${source.configName}.$secret"
                    else ->
                        source.environment?.let { "$it.${source.configName}.$secret" }
                } ?: return@mapNotNull null
                reference.takeIf { it.startsWith(prefix) && it.none(Char::isWhitespace) }
            }
        }
        .distinct()
        .sortedWith(compareBy<String>({ it.length }, { it }))
        .toList()
}

class DopplerSecretReferenceCompletionContributor : CompletionContributor() {
    init {
        extend(
            com.intellij.codeInsight.completion.CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val file = parameters.originalFile.virtualFile as? DopplerSecretsFile ?: return
                    val offset = parameters.offset
                    val completionContext = SecretReferenceCompletion.contextAt(
                        parameters.editor.document.charsSequence,
                        offset,
                    ) ?: return
                    val current = SecretReferenceSource(
                        file.projectSlug,
                        file.environment,
                        file.configName,
                        file.remoteSnapshot.keys,
                    )
                    val sources = DopplerSecretsEditorService.getInstance(parameters.position.project)
                        .secretReferenceSources(file, parameters.editor)
                    val matcher = result.withPrefixMatcher(completionContext.prefix)
                    SecretReferenceCompletion.candidates(current, sources, completionContext.prefix)
                        .forEach { reference ->
                            matcher.addElement(
                                LookupElementBuilder.create(reference)
                                    .withPresentableText(reference)
                                    .withInsertHandler { insertionContext, _ ->
                                        val document = insertionContext.document
                                        val chars = document.charsSequence
                                        val start = chars.lastIndexOf("\${", insertionContext.startOffset - 1)
                                        if (start < 0) return@withInsertHandler
                                        val close = chars.indexOf("}", insertionContext.tailOffset)
                                        val end = if (close >= 0) close + 1 else insertionContext.tailOffset
                                        document.replaceString(start, end, "\${$reference}")
                                        insertionContext.editor.caretModel.moveToOffset(start + reference.length + 3)
                                    },
                            )
                        }
                }
            },
        )
    }
}

class DopplerSecretReferenceTypedHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: com.intellij.openapi.fileTypes.FileType,
    ): Result {
        if (charTyped.isWhitespace() &&
            file.virtualFile is DopplerSecretsFile &&
            SecretReferenceCompletion.contextAt(editor.document.charsSequence, editor.caretModel.offset) != null
        ) {
            return Result.STOP
        }
        return Result.CONTINUE
    }

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '{') return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, Condition { currentFile ->
            val offset = editor.caretModel.offset
            currentFile.virtualFile is DopplerSecretsFile &&
                offset >= 2 &&
                editor.document.charsSequence[offset - 2] == '$' &&
                editor.document.charsSequence[offset - 1] == '{'
        })
        return Result.CONTINUE
    }
}
