package com.vk.doppler.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One config's secrets, held entirely in memory — the bytes are never written to disk.
 *
 * FileDocumentManager saves update this in-memory buffer. Remote pushes happen only after an
 * explicit IDE save action or the Save choice in the close prompt.
 */
class DopplerSecretsFile(
    private val fileSystem: DopplerFileSystem,
    val projectSlug: String,
    val configName: String,
    val authContextId: String,
) : VirtualFile() {

    @Volatile
    var environment: String? = null

    @Volatile
    private var content: ByteArray = EMPTY_DOCUMENT.toByteArray(StandardCharsets.UTF_8)

    @Volatile
    private var modificationStamp: Long = 0L

    @Volatile
    private var timeStamp: Long = System.currentTimeMillis()

    /** Last known server state, used to compute a minimal diff on save. */
    @Volatile
    var remoteSnapshot: Map<String, String?> = emptyMap()

    /** A local save is not necessarily a successful remote save. */
    @Volatile
    var hasPendingSync: Boolean = false

    /** Guards against overlapping pushes when the user saves repeatedly. */
    val pushInProgress: AtomicBoolean = AtomicBoolean(false)

    /** Latest saved document to push after the current sync finishes. */
    val pendingPushText: AtomicReference<String?> = AtomicReference(null)

    /** Set while the content is being replaced with server state, to avoid echoing it back. */
    @Volatile
    var suppressPush: Boolean = false

    @Volatile
    var isInvalidated: Boolean = false
        private set

    override fun getName(): String = fileName(configName)

    /** Navigation labels show the config name; the underlying name retains YAML detection. */
    override fun getPresentableName(): String = configName

    override fun getFileSystem(): VirtualFileSystem = fileSystem

    override fun getPath(): String = pathFor(projectSlug, configName, authContextId)

    override fun isWritable(): Boolean = !isInvalidated

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile>? = null

    override fun contentsToByteArray(): ByteArray = content

    override fun getInputStream(): InputStream = ByteArrayInputStream(content)

    override fun getLength(): Long = content.size.toLong()

    override fun getTimeStamp(): Long = timeStamp

    override fun getModificationStamp(): Long = modificationStamp

    override fun getCharset(): Charset = StandardCharsets.UTF_8

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                if (isInvalidated) return
                val bytes = toByteArray()
                applyContent(bytes, newModificationStamp, newTimeStamp)
                if (!suppressPush) {
                    hasPendingSync = true
                }
            }
        }

    /** Replaces the content with server state without triggering a push back. */
    fun setServerContent(text: String, snapshot: Map<String, String?>) {
        isInvalidated = false
        remoteSnapshot = snapshot
        hasPendingSync = false
        applyContent(text.toByteArray(StandardCharsets.UTF_8), newModificationStamp = -1, newTimeStamp = -1)
    }

    /** Clears secret values from memory once the config is no longer shown anywhere. */
    fun scrub() {
        isInvalidated = true
        remoteSnapshot = emptyMap()
        pendingPushText.set(null)
        hasPendingSync = false
        applyContent(EMPTY_DOCUMENT.toByteArray(StandardCharsets.UTF_8), newModificationStamp = -1, newTimeStamp = -1)
    }

    private fun applyContent(bytes: ByteArray, newModificationStamp: Long, newTimeStamp: Long) {
        content = bytes
        modificationStamp = if (newModificationStamp >= 0) newModificationStamp else modificationStamp + 1
        timeStamp = if (newTimeStamp > 0) newTimeStamp else System.currentTimeMillis()
    }

    companion object {
        val EMPTY_DOCUMENT: String = SecretsDocumentFormat.serialize(emptyMap())

        /** The `.yaml` suffix gives the tab YAML highlighting without a hard plugin dependency. */
        fun fileName(configName: String): String = "$configName.yaml"

        fun pathFor(projectSlug: String, configName: String, authContextId: String): String =
            "/$projectSlug/$authContextId/${fileName(configName)}"
    }
}
