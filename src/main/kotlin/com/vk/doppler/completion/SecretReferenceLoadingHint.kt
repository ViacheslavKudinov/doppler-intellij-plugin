package com.vk.doppler.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Timer

/** A transient editor decoration; never inserts text into the secrets document. EDT only. */
internal class SecretReferenceLoadingHint private constructor(
    private val editor: Editor,
    private val isEditorActive: () -> Boolean,
    private var hasSuggestions: () -> Boolean,
    private var isLoading: () -> Boolean,
) {
    private var inlay: Inlay<*>? = null
    private var displayedMessage: String? = null
    private val timer = Timer(200) { update() }

    internal fun update() {
        if (editor.isDisposed || !isLoading() ||
            SecretReferenceParser.contextAt(editor.document.charsSequence, editor.caretModel.offset) == null) {
            timer.stop()
            inlay?.dispose()
            inlay = null
            editor.putUserData(KEY, null)
            return
        }
        if (!isEditorActive()) {
            inlay?.dispose()
            inlay = null
            return
        }
        val offset = editor.caretModel.offset
        val message = if (hasSuggestions()) MORE_MESSAGE else MESSAGE
        if (inlay?.isValid == true && inlay?.offset == offset && displayedMessage == message) return
        inlay?.dispose()
        displayedMessage = message
        inlay = editor.inlayModel.addInlineElement(offset, true, Renderer(message))
    }

    companion object {
        private val KEY = Key.create<SecretReferenceLoadingHint>("doppler.reference.loading")
        const val MESSAGE = "  Loading Doppler references… this may take a moment"
        internal const val MORE_MESSAGE = "  Loading more Doppler references…"

        internal fun needsLoadingHint(text: CharSequence, offset: Int, location: SecretReferenceLocation, names: SecretNames): Boolean {
            val reference = SecretReferenceParser.contextAt(text, offset) ?: return false
            return SecretReferenceCandidates.find(location, SecretReferenceParser.secretNames(text), names, reference.prefix).isEmpty()
        }

        fun show(
            editor: Editor,
            isEditorActive: () -> Boolean = { SecretReferenceEditorFocus.isActive(editor) },
            hasSuggestions: () -> Boolean = { false },
            isLoading: () -> Boolean,
        ): SecretReferenceLoadingHint {
            editor.getUserData(KEY)?.let { it.isLoading = isLoading; it.hasSuggestions = hasSuggestions; it.update(); return it }
            val hint = SecretReferenceLoadingHint(editor, isEditorActive, hasSuggestions, isLoading)
            editor.putUserData(KEY, hint)
            hint.timer.start()
            hint.update()
            return hint
        }
    }

    internal class Renderer(val message: String) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
                .stringWidth(message).coerceAtLeast(1)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            g.font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g.color = JBColor.GRAY
            g.drawString(message, targetRegion.x, targetRegion.y + inlay.editor.ascent)
        }
    }
}
