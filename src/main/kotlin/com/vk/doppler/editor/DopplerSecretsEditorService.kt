package com.vk.doppler.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.wm.WindowManager
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.notifications.DopplerNotifier
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.SecretChanges
import com.vk.doppler.settings.DopplerSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val HEARTBEAT_SECONDS = 1L

/**
 * Owns the in-memory secret editors for one IDE project and performs the two-way sync.
 */
@Service(Service.Level.PROJECT)
class DopplerSecretsEditorService(private val project: Project) : Disposable {

    private val openPaths = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastPollAt = 0L

    // A cheap fixed heartbeat; the configured interval is re-read on each tick so that changing
    // it in settings takes effect without rescheduling.
    private val pollHandle: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::pollOpenConfigs, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file !is DopplerSecretsFile || source.isFileOpen(file)) return
                    openPaths.remove(file.path)
                    releaseIfUnused(file)
                }
            },
        )
    }

    override fun dispose() {
        pollHandle.cancel(false)
    }

    fun owns(file: DopplerSecretsFile): Boolean = openPaths.contains(file.path)

    /**
     * Drops a config's secrets from memory once no project shows it any more, so values do not
     * linger for the lifetime of the IDE. A running sync releases the file when it finishes.
     */
    private fun releaseIfUnused(file: DopplerSecretsFile) {
        if (file.pushInProgress.get() || findOwner(file) != null) return
        DopplerFileSystem.getInstance().forget(file)
        file.scrub()
    }

    /** Fetches a config's secrets and opens (or refreshes) its editor tab. */
    fun openConfig(projectSlug: String, configName: String) {
        runLoad(projectSlug, configName, "Opening $projectSlug/$configName…") { file ->
            openPaths.add(file.path)
            // Preview tab, like the VS Code extension: browsing configs reuses one tab and only
            // editing promotes it to a permanent one. OpenFileDescriptor is used rather than
            // FileEditorOpenOptions because the latter is a Kotlin data class whose synthetic
            // constructor signature changes between IDE releases.
            val descriptor = OpenFileDescriptor(project, file)
            descriptor.isUsePreviewTab = true
            FileEditorManager.getInstance(project).openEditor(descriptor, true)
        }
    }

    /** Re-reads the config from Doppler, discarding unsaved local edits. */
    fun reload(file: DopplerSecretsFile) {
        runLoad(file.projectSlug, file.configName, "Reloading ${file.projectSlug}/${file.configName}…") {
            DopplerNotifier.info(project, "Reloaded ${file.projectSlug}/${file.configName} from Doppler.")
        }
    }

    private fun runLoad(
        projectSlug: String,
        configName: String,
        title: String,
        onReady: (DopplerSecretsFile) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private lateinit var snapshot: Map<String, String?>
            private lateinit var text: String

            override fun run(indicator: ProgressIndicator) {
                val secrets = DopplerService.getInstance().listSecrets(projectSlug, configName)
                snapshot = secrets.associate { it.name to it.raw }
                text = SecretsDocumentFormat.serialize(secrets)
            }

            override fun onSuccess() {
                val file = DopplerFileSystem.getInstance().obtain(projectSlug, configName)
                if (file.pushInProgress.get()) {
                    DopplerNotifier.error(project, "Wait for the current sync before reloading $projectSlug/$configName.")
                    return
                }
                onReady(materialize(projectSlug, configName, text, snapshot))
            }

            override fun onThrowable(error: Throwable) {
                DopplerNotifier.error(project, "Could not load $projectSlug/$configName: ${error.message}")
            }
        })
    }

    private fun materialize(
        projectSlug: String,
        configName: String,
        text: String,
        snapshot: Map<String, String?>,
    ): DopplerSecretsFile {
        val file = DopplerFileSystem.getInstance().obtain(projectSlug, configName)
        applyServerContent(file, text, snapshot)
        return file
    }

    /** Replaces both the file and its open document with server state, without pushing back. */
    private fun applyServerContent(file: DopplerSecretsFile, text: String, snapshot: Map<String, String?>) {
        file.suppressPush = true
        try {
            file.setServerContent(text, snapshot)
            val documents = FileDocumentManager.getInstance()
            documents.getCachedDocument(file)?.let { document ->
                if (document.text == text) return@let
                WriteCommandAction.runWriteCommandAction(project, "Reload Doppler Secrets", null, {
                    document.setText(text)
                    // Saving keeps the tab from showing as modified; the push is suppressed.
                    documents.saveDocument(document)
                })
            }
        } finally {
            file.suppressPush = false
        }
    }

    /**
     * Re-reads every open config so edits made in the Doppler dashboard (or by a teammate)
     * show up in the editor. Runs on a pooled thread.
     */
    private fun pollOpenConfigs() {
        if (project.isDisposed || WindowManager.getInstance().getFrame(project)?.isActive != true) return

        val interval = DopplerSettings.getInstance().refreshIntervalSeconds
        if (interval <= 0 || openPaths.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastPollAt < interval * 1000L) return
        lastPollAt = now

        val fileSystem = DopplerFileSystem.getInstance()
        val documents = FileDocumentManager.getInstance()
        for (path in openPaths.toList()) {
            if (project.isDisposed) return
            val file = fileSystem.findFileByPath(path) as? DopplerSecretsFile ?: continue
            if (file.pushInProgress.get() || file.hasPendingSync) continue
            val document = documents.getCachedDocument(file)
            if (document != null && documents.isDocumentUnsaved(document)) continue
            refreshFromServer(file)
        }
    }

    private fun refreshFromServer(file: DopplerSecretsFile) {
        val snapshotBeforeFetch = file.remoteSnapshot
        val secrets = try {
            DopplerService.getInstance().listSecrets(file.projectSlug, file.configName)
        } catch (e: Exception) {
            // Auto-refresh is best effort; a transient CLI or network failure must stay silent.
            thisLogger().debug("Doppler auto-refresh failed for ${file.path}", e)
            return
        }

        val snapshot = secrets.associate { it.name to it.raw }
        if (snapshot == file.remoteSnapshot) return
        val text = SecretsDocumentFormat.serialize(secrets)

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !owns(file) || file.pushInProgress.get() || file.hasPendingSync) return@invokeLater
            if (file.remoteSnapshot != snapshotBeforeFetch) return@invokeLater
            val documents = FileDocumentManager.getInstance()
            val document = documents.getCachedDocument(file)
            // Never discard edits the user has not saved yet.
            if (document != null && documents.isDocumentUnsaved(document)) return@invokeLater
            applyServerContent(file, text, snapshot)
        }, project.disposed)
    }

    /**
     * Pushes the edited document to Doppler as a minimal set of writes.
     * Called from the VFS write hook on the EDT, so the work is handed to a background task.
     */
    fun push(file: DopplerSecretsFile, text: String) {
        file.hasPendingSync = true
        if (file.pushInProgress.get()) {
            file.pendingPushText.set(text)
            return
        }

        val edited = try {
            SecretsDocumentFormat.parse(text)
        } catch (e: SecretsFormatException) {
            DopplerNotifier.error(project, "Not saved to Doppler — ${e.message}")
            return
        }

        val changes = SecretChanges.between(file.remoteSnapshot, edited)
        if (changes.isEmpty) {
            file.hasPendingSync = false
            return
        }

        if (!file.pushInProgress.compareAndSet(false, true)) {
            file.pendingPushText.set(text)
            return
        }

        val label = "${file.projectSlug}/${file.configName}"
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Syncing $label to Doppler…", false) {
                private var refreshedText: String? = null
                private var refreshedSnapshot: Map<String, String?> = emptyMap()

                override fun run(indicator: ProgressIndicator) {
                    val service = DopplerService.getInstance()
                    try {
                        service.applyChanges(file.projectSlug, file.configName, changes)
                    } catch (e: DopplerCliException) {
                        // Earlier operations may have succeeded; keep local edits, but rebase
                        // the next save on the actual server state when it can be read.
                        try {
                            file.remoteSnapshot = service.listSecrets(file.projectSlug, file.configName)
                                .associate { it.name to it.raw }
                        } catch (refreshError: DopplerCliException) {
                            thisLogger().warn("Could not refresh Doppler config after a failed sync ${file.path}", refreshError)
                        }
                        throw e
                    }
                    file.remoteSnapshot = changes.applyTo(file.remoteSnapshot)
                    refreshedSnapshot = file.remoteSnapshot
                    refreshedText = SecretsDocumentFormat.serialize(refreshedSnapshot)

                    // Re-read so the document shows exactly what Doppler now holds; otherwise a
                    // cleared line lingers, and the snapshot already matches so no later poll
                    // would ever correct it.
                    try {
                        val secrets = service.listSecrets(file.projectSlug, file.configName)
                        refreshedSnapshot = secrets.associate { it.name to it.raw }
                        refreshedText = SecretsDocumentFormat.serialize(secrets)
                    } catch (e: DopplerCliException) {
                        thisLogger().warn("Could not refresh Doppler config after syncing ${file.path}", e)
                    }
                }

                override fun onSuccess() {
                    val latestDocument = FileDocumentManager.getInstance().getCachedDocument(file)
                    val hasNewerEdits = file.pendingPushText.get() != null ||
                        (latestDocument != null &&
                            (latestDocument.text != text ||
                                FileDocumentManager.getInstance().isDocumentUnsaved(latestDocument)))
                    file.hasPendingSync = hasNewerEdits
                    refreshedText?.let {
                        file.remoteSnapshot = refreshedSnapshot
                        if (!hasNewerEdits) applyServerContent(file, it, refreshedSnapshot)
                    }
                    DopplerNotifier.info(
                        project,
                        "Synced $label — ${changes.upserted.size} updated, ${changes.removed.size} deleted.",
                    )
                }

                override fun onThrowable(error: Throwable) {
                    DopplerNotifier.error(project, "Failed to sync $label: ${error.message}")
                }

                override fun onFinished() {
                    file.pushInProgress.set(false)
                    val pendingText = file.pendingPushText.getAndSet(null)
                    if (pendingText != null) {
                        push(file, pendingText)
                    } else if (!owns(file)) {
                        releaseIfUnused(file)
                    }
                }
            },
        )
    }

    companion object {
        fun getInstance(project: Project): DopplerSecretsEditorService =
            project.getService(DopplerSecretsEditorService::class.java)

        /** Locates the project whose tool window opened [file], so pushes report to the right place. */
        fun findOwner(file: DopplerSecretsFile): DopplerSecretsEditorService? =
            ProjectManager.getInstance().openProjects
                .asSequence()
                .filterNot { it.isDisposed }
                .mapNotNull { it.getServiceIfCreated(DopplerSecretsEditorService::class.java) }
                .firstOrNull { it.owns(file) }
    }
}
