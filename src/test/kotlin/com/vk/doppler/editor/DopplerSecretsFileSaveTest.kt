package com.vk.doppler.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tabs.TabInfo
import javax.swing.JPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import com.vk.doppler.DopplerIcons
import java.nio.charset.StandardCharsets

/**
 * Guards the core assumption behind the two-way sync: an ordinary editor save must reach
 * [DopplerSecretsFile.getOutputStream], which is where the push to Doppler is triggered.
 *
 * A `LightVirtualFile` silently fails this, because the platform never marks documents in a
 * non-physical file system as unsaved.
 */
class DopplerSecretsFileSaveTest : BasePlatformTestCase() {

    private fun newFile(configName: String, text: String, snapshot: Map<String, String?>): DopplerSecretsFile =
        DopplerFileSystem.getInstance().obtain("backend", configName, "test-context").also {
            it.setServerContent(text, snapshot)
        }

    fun `test the file system is registered under the doppler protocol`() {
        assertNotNull(DopplerFileSystem.getInstance())
    }

    fun `test auto refresh pauses for edits and resumes after undo despite unsaved flag`() {
        val snapshot = mapOf("A" to "old")
        val initialText = SecretsDocumentFormat.serialize(snapshot)
        val file = newFile("refresh-undo", initialText, snapshot)
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val service = DopplerSecretsEditorService.getInstance(project)
        assertTrue(service.canAutoRefresh(file))

        WriteAction.run<RuntimeException> { document.setText("A: changed") }
        assertFalse(service.canAutoRefresh(file))
        WriteAction.run<RuntimeException> { document.setText(initialText) }
        assertTrue(documents.isDocumentUnsaved(document))
        assertTrue(service.canAutoRefresh(file))
        // The same guard rejects edits started while a refresh is in flight.
        WriteAction.run<RuntimeException> { document.setText("A: changed again") }
        assertFalse(service.canAutoRefresh(file))
    }

    fun `test auto refresh requires active window and selected clean config`() {
        val text = SecretsDocumentFormat.serialize(emptyMap())
        val selected = newFile("refresh-selected", text, emptyMap())
        val background = newFile("refresh-background", text, emptyMap())
        val service = DopplerSecretsEditorService.getInstance(project)
        assertEmpty(service.autoRefreshFiles(false, arrayOf(selected)))
        assertEmpty(service.autoRefreshFiles(true, arrayOf(LightVirtualFile("ordinary.yaml"))))
        assertEmpty(service.autoRefreshFiles(true, emptyArray()))
        assertEquals(listOf(selected), service.autoRefreshFiles(true, arrayOf(selected)))
        assertFalse(service.autoRefreshFiles(true, arrayOf(selected)).contains(background))
        selected.hasPendingSync = true
        assertEmpty(service.autoRefreshFiles(true, arrayOf(selected)))
        assertEquals(listOf(background), service.autoRefreshFiles(true, arrayOf(selected, background)))
    }

    fun `test auto refresh resumes after successful sync but not local save`() {
        val file = newFile("refresh-save", SecretsDocumentFormat.serialize(mapOf("A" to "old")), mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val service = DopplerSecretsEditorService.getInstance(project)
        val savedSnapshot = mapOf("A" to "new")
        val savedText = SecretsDocumentFormat.serialize(savedSnapshot)
        WriteAction.run<RuntimeException> { document.setText(savedText); documents.saveDocument(document) }
        assertFalse(service.canAutoRefresh(file))
        file.pushInProgress.set(true)
        file.setServerContent(savedText, savedSnapshot)
        assertFalse(service.canAutoRefresh(file))
        file.pushInProgress.set(false)
        assertTrue(service.canAutoRefresh(file))

        WriteAction.run<RuntimeException> { document.setText("A: newer edit") }
        assertFalse(service.canAutoRefresh(file))
    }

    fun `test auto refresh skips pending failed queued and invalidated configs`() {
        val file = newFile("refresh-pending", SecretsDocumentFormat.serialize(emptyMap()), emptyMap())
        val other = newFile("refresh-other", SecretsDocumentFormat.serialize(emptyMap()), emptyMap())
        val service = DopplerSecretsEditorService.getInstance(project)
        assertTrue(service.canAutoRefresh(file))
        file.hasPendingSync = true
        assertFalse(service.canAutoRefresh(file))
        assertTrue(service.canAutoRefresh(other))
        file.hasPendingSync = false
        file.pendingPushText.set("A: queued")
        assertFalse(service.canAutoRefresh(file))
        file.pendingPushText.set(null)
        service.push(file, "A: [unclosed")
        assertFalse(service.canAutoRefresh(file))
        file.scrub()
        assertFalse(service.canAutoRefresh(file))
    }

    fun `test close check is registered and leaves ordinary files unaffected`() {
        val check = VirtualFilePreCloseCheck.EP_NAME.extensionList.filterIsInstance<DopplerFileCloseCheck>().single()
        assertTrue(check.canCloseFile(LightVirtualFile("ordinary.yaml")))
        assertTrue(check.canCloseFile(newFile("close-clean", "A: old", mapOf("A" to "old"))))
    }

    fun `test cancel close preserves unsaved document and pending sync`() {
        val file = newFile("close-cancel", "A: old", mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: new") }
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 2 })
        assertEquals("A: new", document.text)
        assertTrue(documents.isDocumentUnsaved(document))
        file.hasPendingSync = true
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { -1 })
        assertTrue(file.hasPendingSync)
    }

    fun `test dont save discards edits without pushing to Doppler`() {
        val file = newFile("close-discard", "A: old", mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: new") }
        file.hasPendingSync = true
        assertTrue(DopplerFileCloseCheck().confirmClose(file, project) { 1 })
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
        assertFalse(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
        assertFalse(documents.isDocumentUnsaved(document))
        assertEquals(SecretsDocumentFormat.serialize(mapOf("A" to "old")), document.text)
    }

    fun `test save permits closing when document already matches Doppler`() {
        val text = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val file = newFile("close-save", text, mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        file.hasPendingSync = true
        assertTrue(DopplerFileCloseCheck().confirmClose(file, project) { 0 })
        assertFalse(file.hasPendingSync)
    }

    fun `test invalid save keeps the tab open and retains local edits`() {
        val file = newFile("close-invalid", "A: old", mapOf("A" to "old"))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: [unclosed") }
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 0 })
        assertEquals("A: [unclosed", document.text)
        assertTrue(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
    }

    fun `test save defers closing until sync completes without pending edits`() {
        val file = newFile("close-deferred", "A: old", mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        val service = DopplerSecretsEditorService.getInstance(project)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        var closes = 0
        assertFalse(service.saveBeforeClose(file) { closes++ })
        assertEquals(0, closes)
        file.pushInProgress.set(false)
        file.hasPendingSync = false
        service.finishCloseRequest(file)
        assertEquals(1, closes)
        service.finishCloseRequest(file)
        assertEquals(1, closes)
    }

    fun `test cancelling a pending close request prevents delayed closure`() {
        val file = newFile("close-cancel-deferred", "A: old", mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        val service = DopplerSecretsEditorService.getInstance(project)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        var closes = 0
        assertFalse(service.saveBeforeClose(file) { closes++ })
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 2 })
        file.pushInProgress.set(false)
        file.hasPendingSync = false
        service.finishCloseRequest(file)
        assertEquals(0, closes)
    }

    fun `test registered file icon provider only overrides Doppler files`() {
        val provider = FileIconProvider.EP_NAME.extensionList.filterIsInstance<DopplerFileIconProvider>().single()
        val file = DopplerFileSystem.getInstance().obtain("icons", "dev", "test-context")

        assertSame(DopplerIcons.EditorTab, provider.getIcon(file, 0, project))
        assertNotNull(DopplerIcons::class.java.getResource("/icons/doppler_tab.svg"))
        assertEquals("YAML", file.fileType.name)
        assertNull(provider.getIcon(LightVirtualFile("ordinary.yaml"), 0, project))
    }

    fun `test duplicate config tabs include project and environment`() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-backend", "dev", "test-context").apply { environment = "development" }
        val second = fs.obtain("tab-frontend", "dev", "test-context").apply { environment = "dev" }
        val manager = FileEditorManager.getInstance(project)
        val provider = DopplerEditorTabTitleProvider()
        manager.openFile(first, false)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.openFile(second, false)

        assertEquals("dev /tab-backend/development/dev", provider.getEditorTabTitle(project, first))
        assertEquals("dev /tab-frontend/dev/dev", provider.getEditorTabTitle(project, second))

        manager.closeFile(second)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.closeFile(first)
    }

    fun `test duplicate config title handles missing environment metadata`() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-known", "stg", "test-context")
        val second = fs.obtain("tab-other", "stg", "test-context")
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(first, false)
        manager.openFile(second, false)

        val title = DopplerEditorTabTitleProvider().getEditorTabTitle(project, first)
        assertEquals("stg /tab-known/stg", title)
        manager.closeFile(second)
        manager.closeFile(first)
    }

    fun `test tab presentation uses bold config and regular path and survives title resets`() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("bold-backend", "dev", "test-context").apply { environment = "dev" }
        val second = fs.obtain("bold-frontend", "dev", "test-context").apply { environment = "dev" }
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(first, false)
        manager.openFile(second, false)
        val presentation = DopplerEditorTabPresentation(project)
        try {
            val tab = TabInfo(JPanel()).setObject(first)
            presentation.update(tab)
            assertEquals(listOf("dev", " /bold-backend/dev/dev"), tab.coloredText.texts)
            assertEquals(SimpleTextAttributes.STYLE_BOLD, tab.coloredText.attributes[0].style)
            assertEquals(SimpleTextAttributes.STYLE_PLAIN, tab.coloredText.attributes[1].style)

            tab.setText("dev /bold-backend/dev/dev")
            assertEquals(SimpleTextAttributes.STYLE_BOLD, tab.coloredText.attributes[0].style)
            assertEquals(SimpleTextAttributes.STYLE_PLAIN, tab.coloredText.attributes[1].style)
            manager.closeFile(second)
            presentation.update(tab)
            assertEquals(listOf("dev"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
            manager.closeFile(second)
            manager.closeFile(first)
        }
    }

    fun `test tab presentation marks unsaved edits while typing`() {
        val initialText = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val editedText = SecretsDocumentFormat.serialize(mapOf("A" to "changed"))
        val file = newFile("editing", initialText, mapOf("A" to "old"))
        val presentation = DopplerEditorTabPresentation(project)
        val tab = TabInfo(JPanel()).setObject(file)
        try {
            presentation.update(tab)
            assertEquals(listOf("editing"), tab.coloredText.texts)

            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            WriteAction.run<RuntimeException> { document.setText(editedText) }

            assertEquals(listOf("editing", " *"), tab.coloredText.texts)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

            WriteAction.run<RuntimeException> { document.setText(initialText) }

            assertTrue("the IDE still considers the reverted document unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))
            assertEquals("undoing back to the server content clears the edit marker", listOf("editing"), tab.coloredText.texts)

            WriteAction.run<RuntimeException> { document.setText(editedText) }
            assertEquals(listOf("editing", " *"), tab.coloredText.texts)

            file.suppressPush = true
            try {
                WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(document) }
            } finally {
                file.suppressPush = false
            }
            file.setServerContent(editedText, mapOf("A" to "changed"))
            presentation.update(tab)

            assertEquals(listOf("editing"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
        }
    }

    fun `test real editor tab presentation tracks undo to server content`() {
        val initialText = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val editedText = SecretsDocumentFormat.serialize(mapOf("A" to "changed"))
        val file = newFile("real-tab-editing", initialText, mapOf("A" to "old"))
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.openFile(file, false).single()
        val presentation = DopplerEditorTabPresentation(project)
        val tab = TabInfo(JPanel()).setObject(editor)
        try {
            presentation.update(tab)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

            WriteAction.run<RuntimeException> { document.setText(editedText) }
            assertEquals(listOf("real-tab-editing", " *"), tab.coloredText.texts)

            WriteAction.run<RuntimeException> { document.setText(initialText) }
            assertEquals(listOf("real-tab-editing"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
            manager.closeFile(file)
        }
    }

    fun `test editing marks the document unsaved and saving writes through to the file`() {
        val file = newFile("dev", "API_KEY: old", mapOf("API_KEY" to "old"))
        val manager = FileDocumentManager.getInstance()
        val document = requireNotNull(manager.getDocument(file))

        assertEquals("API_KEY: old", document.text)

        WriteAction.run<RuntimeException> { document.setText("API_KEY: new") }
        assertTrue("document should be pending save", manager.isDocumentUnsaved(document))

        WriteAction.run<RuntimeException> { manager.saveDocument(document) }

        assertFalse("document should be saved", manager.isDocumentUnsaved(document))
        assertTrue("local save should remain pending for explicit remote sync", file.hasPendingSync)
        assertFalse("local save must not start a remote push", file.pushInProgress.get())
        assertEquals(
            "API_KEY: new",
            String(file.contentsToByteArray(), StandardCharsets.UTF_8),
        )
    }

    fun `test the file system returns the same instance for a config`() {
        val fs = DopplerFileSystem.getInstance()
        assertSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "stg", "test-context"))
        assertNotSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "prd", "test-context"))
        assertNotSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "stg", "other-context"))
    }

    fun `test invalidating another auth context closes and scrubs the cached editor`() {
        val fs = DopplerFileSystem.getInstance()
        val secretText = SecretsDocumentFormat.serialize(mapOf("API_TOKEN" to "secret-value"))
        val file = newFile("auth-scrub", secretText, mapOf("API_TOKEN" to "secret-value"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, false)

        DopplerSecretsEditorService.invalidateCachedFiles("other-context")

        assertTrue(file.isInvalidated)
        assertFalse(file.isWritable)
        assertTrue(file.remoteSnapshot.isEmpty())
        assertFalse(String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("secret-value"))
        assertFalse(document.text.contains("secret-value"))
        assertFalse(manager.isFileOpen(file))
        assertNull(fs.findFileByPath(file.path))
    }

    fun `test the file is resolvable by path and is writable yaml`() {
        val file = newFile("prd", DopplerSecretsFile.EMPTY_DOCUMENT, emptyMap())

        assertTrue(file.isWritable)
        assertEquals("YAML", file.fileType.name)
        assertEquals("prd.yaml", file.name)
        assertEquals("prd", file.presentableName)
        assertSame(file, DopplerFileSystem.getInstance().findFileByPath(file.path))
    }

    fun `test navigation label shows config name without synthetic yaml extension`() {
        val file = newFile("stg", DopplerSecretsFile.EMPTY_DOCUMENT, emptyMap())
        myFixture.configureFromExistingVirtualFile(file)
        val psiFile = myFixture.file

        assertEquals("stg", com.intellij.ide.navigationToolbar.DefaultNavBarExtension().getPresentableText(psiFile))
        assertEquals("YAML", psiFile.fileType.name)
    }

    fun `test reloading server content does not push back`() {
        val file = newFile("ci", "A: '1'", mapOf("A" to "1"))
        file.suppressPush = true
        file.setServerContent("A: '2'", mapOf("A" to "2"))
        file.suppressPush = false

        assertEquals(mapOf("A" to "2"), file.remoteSnapshot)
        assertEquals("A: '2'", String(file.contentsToByteArray(), StandardCharsets.UTF_8))
    }

    fun `test a saved local edit remains pending until synchronized`() {
        val file = newFile("pending", "A: old", mapOf("A" to "old"))
        assertFalse(file.hasPendingSync)
        file.getOutputStream(null, -1, -1).use { it.write("A: new".toByteArray(StandardCharsets.UTF_8)) }
        assertTrue(file.hasPendingSync)

        file.setServerContent("A: new", mapOf("A" to "new"))
        assertFalse(file.hasPendingSync)
    }

    fun `test a rejected save remains protected from auto refresh`() {
        val file = newFile("invalid", "A: old", mapOf("A" to "old"))
        DopplerSecretsEditorService.getInstance(project).push(file, "A: [unclosed")

        assertTrue(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
    }

    fun `test a save without remote changes clears the pending state`() {
        val file = newFile("unchanged", "A: old", mapOf("A" to "old"))
        file.hasPendingSync = true
        DopplerSecretsEditorService.getInstance(project).push(file, "A: old")

        assertFalse(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
    }

    fun `test saves during a sync keep only the newest pending document`() {
        val file = newFile("queued", "A: old", mapOf("A" to "old"))
        val service = DopplerSecretsEditorService.getInstance(project)
        file.pushInProgress.set(true)

        service.push(file, "A: first")
        service.push(file, "A: latest")

        assertTrue(file.hasPendingSync)
        assertEquals("A: latest", file.pendingPushText.get())
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
        file.pushInProgress.set(false)
        file.pendingPushText.set(null)
    }
}
