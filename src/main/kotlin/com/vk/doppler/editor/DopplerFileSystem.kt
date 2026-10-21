package com.vk.doppler.editor

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal in-memory file system for Doppler configs.
 *
 * It deliberately does *not* implement `NonPhysicalFileSystem`: the platform skips saving
 * documents that belong to non-physical file systems, and saving is exactly what triggers
 * the push back to Doppler.
 */
class DopplerFileSystem : DeprecatedVirtualFileSystem() {

    private val files = ConcurrentHashMap<String, DopplerSecretsFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? = files[path]

    /** Presents Doppler identities without exposing the internal auth-context path segment. */
    override fun extractPresentableUrl(path: String): String {
        files[path]?.let { return it.displayPath }
        // Forgotten files still need safe presentation, but their environment metadata is no longer available.
        val projectPath = path.substringBeforeLast('/').substringBeforeLast('/')
        val configName = path.substringAfterLast('/').removeSuffix(".yaml")
        return "$projectPath/$configName"
    }

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    /** Returns the existing file for the config, creating it on first use. */
    fun obtain(projectSlug: String, configName: String, authContextId: String): DopplerSecretsFile =
        files.computeIfAbsent(DopplerSecretsFile.pathFor(projectSlug, configName, authContextId)) {
            DopplerSecretsFile(this, projectSlug, configName, authContextId)
        }

    fun cachedFiles(): List<DopplerSecretsFile> = files.values.toList()

    fun forget(file: DopplerSecretsFile) {
        files.remove(file.path, file)
    }

    companion object {
        const val PROTOCOL: String = "doppler"

        fun getInstance(): DopplerFileSystem =
            VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as DopplerFileSystem
    }
}
