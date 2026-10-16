package com.vk.doppler.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.editor.DopplerFileSystem
import com.vk.doppler.editor.SecretsDocumentFormat

class SecretReferenceTypedHandlerTest : BasePlatformTestCase() {
    fun `test typing repeatedly inside existing reference requests completion each time`() {
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

    fun `test ordinary file does not trigger Doppler completion`() {
        myFixture.configureByText("ordinary.txt", "VALUE: \${API<caret>")
        var attempts = 0
        val handler = DopplerSecretReferenceTypedHandler { _, _ -> attempts++ }
        handler.charTyped('I', project, myFixture.editor, myFixture.file)
        assertEquals(0, attempts)
    }
}
