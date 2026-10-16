package com.vk.doppler.completion

import com.intellij.openapi.editor.Editor
import com.intellij.codeInsight.lookup.LookupManager
import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

/** Completion belongs to the focused editor, including when a background load finishes later. */
internal object SecretReferenceEditorFocus {
    fun isActive(
        editor: Editor,
        focusOwner: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
        windowActive: Boolean = SwingUtilities.getWindowAncestor(editor.component)?.isActive == true ||
            LookupManager.getActiveLookup(editor)?.component?.let { SwingUtilities.getWindowAncestor(it)?.isActive } == true,
        lookupComponent: Component? = LookupManager.getActiveLookup(editor)?.component,
    ): Boolean = !editor.isDisposed && editor.project?.isDisposed != true && windowActive &&
        focusOwner != null && (focusOwner === editor.contentComponent ||
        SwingUtilities.isDescendingFrom(focusOwner, editor.contentComponent) ||
        (lookupComponent != null && (focusOwner === lookupComponent || SwingUtilities.isDescendingFrom(focusOwner, lookupComponent))))
}
