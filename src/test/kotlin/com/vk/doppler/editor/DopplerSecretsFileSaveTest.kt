package com.vk.doppler.editor

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
