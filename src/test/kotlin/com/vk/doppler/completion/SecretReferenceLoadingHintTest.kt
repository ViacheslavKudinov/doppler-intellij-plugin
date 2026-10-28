package com.vk.doppler.completion

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SecretReferenceLoadingHintTest : BasePlatformTestCase() {
    fun testLoadingRemainsAtCaretWithSuggestionsAndPopupDismissal() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret> remaining text")
        val editor = myFixture.editor
        val originalText = editor.document.text
        val offset = editor.caretModel.offset
        val lookup = LookupManager.getInstance(project).showLookup(editor, LookupElementBuilder.create("LOCAL")) as LookupImpl
        var loading = true
        var matches = true
        val hint = SecretReferenceLoadingHint.show(editor, { true }, { matches }) { loading }

        fun message(): String {
            val inlay = editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength).single()
            assertEquals(offset, inlay.offset)
            return (inlay.renderer as SecretReferenceLoadingHint.Renderer).message
        }
        try {
            assertEquals(SecretReferenceLoadingHint.MORE_MESSAGE, message())
            lookup.hideLookup(true)
            hint.update()
            assertEquals(SecretReferenceLoadingHint.MORE_MESSAGE, message())
            matches = false
            hint.update()
            assertEquals(SecretReferenceLoadingHint.MESSAGE, message())
            assertEquals(originalText, editor.document.text)
            loading = false
            hint.update()
            assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        } finally {
            loading = false
            hint.update()
            if (!lookup.isLookupDisposed) lookup.hideLookup(true)
        }
    }

    fun testMatchingLocalAndCachedSuggestionsSelectTheMoreReferencesMessage() {
        val location = SecretReferenceLocation("app", "dev")

        fun needsHint(
            prefix: String,
            names: SecretNames = emptyMap(),
        ): Boolean {
            val text = "LOCAL: example\nVALUE: \${$prefix"
            return SecretReferenceLoadingHint.needsLoadingHint(text, text.length, location, names)
        }
        assertFalse(needsHint("LOC"))
        assertTrue(needsHint("other."))
        val names = mapOf("other" to mapOf("dev" to setOf("KEY")))
        assertFalse(needsHint("other.", names))
        assertTrue(needsHint("missing.", names))
        assertFalse(needsHint("other.dev.K", names))
    }

    fun testRepeatedCompletionReplacesStaleHintPredicate() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        SecretReferenceLoadingHint.show(editor, { true }) { true }
        SecretReferenceLoadingHint.show(editor, { true }) { false }
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        val restarted = SecretReferenceLoadingHint.show(editor, { true }) { true }
        try {
            assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        } finally {
            editor.caretModel.moveToOffset(0)
            restarted.update()
        }
    }

    fun testFocusLossRemovesLoadingHintAndALateRequestCannotReopenIt() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        var focused = true
        val hint = SecretReferenceLoadingHint.show(editor, { focused }) { true }
        assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        focused = false
        hint.update()
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        SecretReferenceLoadingHint.show(editor, { focused }) { true }
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        focused = true
        hint.update()
        assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        editor.caretModel.moveToOffset(0)
        hint.update()
    }

    fun testHintCanAppearAgainAfterLeavingAndReenteringAReference() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        val first = SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { true }
        editor.caretModel.moveToOffset(0)
        first.update()
        editor.caretModel.moveToOffset(offset)
        var loading = true
        val second = SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { loading }
        try {
            assertNotSame(first, second)
            assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
        } finally {
            loading = false
            second.update()
        }
    }

    fun testLoadingHintIsDecorationAndDisappearsWhenLoadingEnds() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        val originalText = editor.document.text
        var loading = true
        val hint = SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { loading }
        try {
            assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
            assertEquals(originalText, editor.document.text)
            assertSame(hint, SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { loading })
            assertSize(1, editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
            loading = false
            hint.update()
            assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
            assertEquals(originalText, editor.document.text)
        } finally {
            loading = false
            hint.update()
        }
    }

    fun testLeavingReferenceClearsHintEvenWhileLoadingContinues() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        val hint = SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { true }
        editor.caretModel.moveToOffset(0)
        hint.update()
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
    }

    fun testCompletedLoadNeverShowsStaleLoadingHint() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        SecretReferenceLoadingHint.show(editor, isEditorActive = { true }) { false }
        assertEmpty(editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength))
    }
}
