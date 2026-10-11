package com.vk.doppler.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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
        DopplerFileSystem.getInstance().obtain("backend", configName).also {
            it.setServerContent(text, snapshot)
        }

    fun `test the file system is registered under the doppler protocol`() {
        assertNotNull(DopplerFileSystem.getInstance())
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
        val file = DopplerFileSystem.getInstance().obtain("icons", "dev")

        assertSame(DopplerIcons.EditorTab, provider.getIcon(file, 0, project))
        assertNotNull(DopplerIcons::class.java.getResource("/icons/doppler_tab.svg"))
        assertEquals("YAML", file.fileType.name)
        assertNull(provider.getIcon(LightVirtualFile("ordinary.yaml"), 0, project))
    }

    fun `test duplicate config tabs include project and environment`() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-backend", "dev").apply { environment = "development" }
        val second = fs.obtain("tab-frontend", "dev").apply { environment = "dev" }
        val manager = FileEditorManager.getInstance(project)
        val provider = DopplerEditorTabTitleProvider()
        manager.openFile(first, false)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.openFile(second, false)

        assertEquals("dev /tab-backend/development/dev", provider.getEditorTabTitle(project, first))
        assertEquals("/tab-backend/development/dev", provider.getEditorTabTooltipText(project, first))
        assertEquals("dev /tab-frontend/dev/dev", provider.getEditorTabTitle(project, second))

        manager.closeFile(second)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.closeFile(first)
    }

    fun `test duplicate config title handles missing environment metadata`() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-known", "stg")
        val second = fs.obtain("tab-other", "stg")
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
        val first = fs.obtain("bold-backend", "dev").apply { environment = "dev" }
        val second = fs.obtain("bold-frontend", "dev").apply { environment = "dev" }
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

    fun `test editing marks the document unsaved and saving writes through to the file`() {
        val file = newFile("dev", "API_KEY: old", mapOf("API_KEY" to "old"))
        val manager = FileDocumentManager.getInstance()
        val document = requireNotNull(manager.getDocument(file))

        assertEquals("API_KEY: old", document.text)

        WriteAction.run<RuntimeException> { document.setText("API_KEY: new") }
        assertTrue("document should be pending save", manager.isDocumentUnsaved(document))

        WriteAction.run<RuntimeException> { manager.saveDocument(document) }

        assertFalse("document should be saved", manager.isDocumentUnsaved(document))
        assertEquals(
            "API_KEY: new",
            String(file.contentsToByteArray(), StandardCharsets.UTF_8),
        )
    }

    fun `test the file system returns the same instance for a config`() {
        val fs = DopplerFileSystem.getInstance()
        assertSame(fs.obtain("backend", "stg"), fs.obtain("backend", "stg"))
        assertNotSame(fs.obtain("backend", "stg"), fs.obtain("backend", "prd"))
    }

    fun `test the file is resolvable by path and is writable yaml`() {
        val file = newFile("prd", DopplerSecretsFile.EMPTY_DOCUMENT, emptyMap())

        assertTrue(file.isWritable)
        assertEquals("YAML", file.fileType.name)
        assertEquals("prd.yaml", file.name)
        assertSame(file, DopplerFileSystem.getInstance().findFileByPath(file.path))
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
