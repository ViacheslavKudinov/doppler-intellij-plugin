package com.vk.doppler.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

class SecretReferenceEditorFocusTest : BasePlatformTestCase() {
    fun testCompletionRequiresBothActiveIdeWindowAndFocusedEditor() {
        myFixture.configureByText("references.txt", "VALUE: \${<caret>")
        val editor = myFixture.editor
        assertTrue(SecretReferenceEditorFocus.isActive(editor, editor.contentComponent, true))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, editor.contentComponent, false))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, null, true))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, JPanel(), true))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, null, false))
        val lookup = JPanel()
        val lookupChild = JPanel()
        lookup.add(lookupChild)
        assertTrue(SecretReferenceEditorFocus.isActive(editor, lookupChild, true, lookup))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, lookupChild, false, lookup))
        assertFalse(SecretReferenceEditorFocus.isActive(editor, JPanel(), true, lookup))
    }
}
