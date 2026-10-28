package com.vk.doppler.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.editor.DopplerFileSystem
import com.vk.doppler.editor.SecretsDocumentFormat

class SecretReferenceTypedHandlerTest : BasePlatformTestCase() {
    fun testWhitespaceIsStoppedOnlyInsideAnActiveReference() {
        val file = DopplerFileSystem.getInstance().obtain("completion", "whitespace", "test-context")
        val text = "VALUE: \${API"
        file.setServerContent(text, emptyMap())
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(text.length)
        val handler = DopplerSecretReferenceTypedHandler { _, _ -> fail("Whitespace must not schedule a popup before insertion") }

        for (whitespace in listOf(' ', '\t', '\n')) {
            assertEquals(
                TypedHandlerDelegate.Result.STOP,
                handler.beforeCharTyped(whitespace, project, editor, myFixture.file, myFixture.file.fileType),
            )
        }
        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.beforeCharTyped('X', project, editor, myFixture.file, myFixture.file.fileType),
        )
        editor.caretModel.moveToOffset(0)
        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.beforeCharTyped(' ', project, editor, myFixture.file, myFixture.file.fileType),
        )
    }

    fun testTypingRepeatedlyInsideExistingReferenceRequestsCompletionEachTime() {
        val file = DopplerFileSystem.getInstance().obtain("completion", "repeat", "test-context")
        val text = "VALUE: \${API"
        file.setServerContent(text, emptyMap())
        myFixture.configureFromExistingVirtualFile(file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(text.length)
        var attempts = 0
        val handler = DopplerSecretReferenceTypedHandler { _, _ -> attempts++ }

        handler.charTyped('I', project, editor, myFixture.file)
        // Simulate returning to edit the same reference after dismissing completion.
        editor.caretModel.moveToOffset(text.length - 1)
        handler.charTyped('P', project, editor, myFixture.file)
        editor.caretModel.moveToOffset(text.length)
        handler.charTyped('I', project, editor, myFixture.file)
        assertEquals(3, attempts)

        editor.caretModel.moveToOffset(0)
        handler.charTyped('V', project, editor, myFixture.file)
        assertEquals(3, attempts)
    }

    fun testOrdinaryFileDoesNotTriggerDopplerCompletion() {
        myFixture.configureByText("ordinary.txt", "VALUE: \${API<caret>")
        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            DopplerSecretReferenceTypedHandler().beforeCharTyped(' ', project, myFixture.editor, myFixture.file, myFixture.file.fileType),
        )
        var attempts = 0
        val handler = DopplerSecretReferenceTypedHandler { _, _ -> attempts++ }
        handler.charTyped('I', project, myFixture.editor, myFixture.file)
        assertEquals(0, attempts)
    }
}
