package com.vk.doppler.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.util.Disposer
import com.vk.doppler.editor.DopplerSecretsFile
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Single-click insertion is scoped to Doppler reference suggestions. */
internal class SecretReferenceClickSelection : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        val lookup = newLookup as? LookupImpl ?: return
        if (lookup.psiFile?.virtualFile !is DopplerSecretsFile) return
        val list = lookup.list
        val listener = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (lookup.isLookupDisposed || !SwingUtilities.isLeftMouseButton(event) ||
                    event.clickCount != 1 || event.modifiersEx != 0) return
                val row = list.locationToIndex(event.point)
                if (row < 0 || list.getCellBounds(row, row)?.contains(event.point) != true) return
                val item = list.model.getElementAt(row)
                if (item.`object` !is SecretReferenceCandidate) return
                // Raw Swing mouse callbacks do not hold the platform's write-intent lock.
                // Completion listeners commit PSI after insertion, so keep the lock for
                // the entire finishLookup call (which already creates its own command).
                WriteIntentReadAction.run {
                    if (SecretReferenceParser.contextAt(lookup.editor.document.charsSequence, lookup.editor.caretModel.offset) == null) return@run
                    lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR, item)
                    event.consume()
                }
            }
        }
        list.addMouseListener(listener)
        Disposer.register(lookup) { list.removeMouseListener(listener) }
    }
}
