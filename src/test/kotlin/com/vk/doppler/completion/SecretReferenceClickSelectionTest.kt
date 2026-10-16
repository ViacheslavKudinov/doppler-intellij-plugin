package com.vk.doppler.completion

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.editor.DopplerFileSystem
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

class SecretReferenceClickSelectionTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = name != "test single click from raw EDT permits completion PSI commit"

    fun `test single click from raw EDT permits completion PSI commit`() {
        lateinit var lookup: LookupImpl
        var committed = false
        runInEdtAndWait {
            lookup = lookup()
            lookup.addLookupListener(object : LookupListener {
                override fun itemSelected(event: LookupEvent) {
                    // The real completion insertion pipeline commits PSI here.
                    ApplicationManager.getApplication().assertWriteIntentLockAcquired()
                    PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
                    committed = true
                }
            })
        }
        try {
            SwingUtilities.invokeAndWait {
                assertFalse(ApplicationManager.getApplication().isWriteIntentLockAcquired)
                // Match the write-safe user activity established by IDE mouse dispatch.
                (TransactionGuard.getInstance() as TransactionGuardImpl).performUserActivity {
                    assertFalse(ApplicationManager.getApplication().isWriteIntentLockAcquired)
                    click(lookup)
                }
            }
            runInEdtAndWait {
                assertTrue(committed)
                assertEquals("VALUE: \${API_KEY", myFixture.editor.document.text)
                assertTrue(lookup.isLookupDisposed)
            }
        } finally {
            runInEdtAndWait {
                if (!lookup.isLookupDisposed) lookup.hideLookup(true)
            }
        }
    }

    private fun lookup(doppler: Boolean = true, marked: Boolean = true): LookupImpl {
        if (doppler) {
            val file = DopplerFileSystem.getInstance().obtain("click", "dev", "test-context")
            file.setServerContent("VALUE: \${", emptyMap())
            myFixture.configureFromExistingVirtualFile(file)
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        } else {
            myFixture.configureByText("ordinary.txt", "VALUE: \${<caret>")
        }
        val item = if (marked) secretReferenceLookupElement(
            SecretReferenceCandidate("API_KEY", SecretReferenceScope.CONFIG, "click/dev"),
        ) else LookupElementBuilder.create("ordinary")
        return LookupManager.getInstance(project).showLookup(myFixture.editor, item) as LookupImpl
    }

    private fun click(lookup: LookupImpl, button: Int = MouseEvent.BUTTON1, count: Int = 1, below: Boolean = false) {
        val list = lookup.list
        // Headless lookups have no laid-out native window; give row hit-testing real bounds.
        list.setSize(600, 200)
        val bounds = requireNotNull(list.getCellBounds(0, 0))
        val event = MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0,
            bounds.x + 2, if (below) bounds.y + bounds.height + 20 else bounds.y + 2,
            count, false, button)
        list.mouseListeners.forEach { it.mouseClicked(event) }
    }

    fun `test single click accepts selected reference through the lookup`() {
        val lookup = lookup()
        click(lookup)
        // showLookup tests acceptance without the full completion contributor insertion pipeline.
        assertEquals("VALUE: \${API_KEY", myFixture.editor.document.text)
        assertTrue(lookup.isLookupDisposed)
    }

    fun `test right double and empty space clicks do not trigger single click insertion`() {
        val lookup = lookup()
        try {
            click(lookup, MouseEvent.BUTTON3)
            click(lookup, count = 2)
            click(lookup, below = true)
            assertEquals("VALUE: \${", myFixture.editor.document.text)
        } finally {
            lookup.hideLookup(true)
        }
    }

    fun `test ordinary editor keeps normal click behavior`() {
        val lookup = lookup(doppler = false)
        try {
            click(lookup)
            assertEquals("VALUE: \${", myFixture.editor.document.text)
        } finally {
            lookup.hideLookup(true)
        }
    }

    fun `test unmarked suggestion in Doppler editor keeps normal click behavior`() {
        val lookup = lookup(marked = false)
        try {
            click(lookup)
            assertEquals("VALUE: \${", myFixture.editor.document.text)
        } finally {
            lookup.hideLookup(true)
        }
    }
}
