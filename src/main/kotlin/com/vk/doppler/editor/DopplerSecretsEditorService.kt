package com.vk.doppler.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ModalityState
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
import com.vk.doppler.completion.SecretReferenceService
import com.vk.doppler.notifications.DopplerNotifier
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerSecretsSnapshot
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
    private val closeAfterSync = mutableMapOf<DopplerSecretsFile, () -> Unit>()
    private val tabPresentation = DopplerEditorTabPresentation(project)

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
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    tabPresentation.refresh()
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    tabPresentation.refresh()
                    if (file !is DopplerSecretsFile || source.isFileOpen(file)) return
                    openPaths.remove(file.path)
                    releaseIfUnused(file)
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AnActionListener.TOPIC,
            object : AnActionListener {
                override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
                    if (ActionManager.getInstance().getId(action) != "SaveAll" || event.project !== project) return
                    val file = FileEditorManager.getInstance(project).selectedEditor?.file as? DopplerSecretsFile
                        ?: return
                    syncPendingConfig(file)
                }
            },
        )
    }

    override fun dispose() {
        pollHandle.cancel(false)
        tabPresentation.dispose()
        closeAfterSync.clear()
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
    fun openConfig(projectSlug: String, configName: String, environment: String? = null) {
        runLoad(projectSlug, configName, "Opening /$projectSlug/$configName…") { file ->
            if (environment != null) file.environment = environment
            openPaths.add(file.path)
              val descriptor = OpenFileDescriptor(project, file)
              descriptor.isUsePreviewTab = true
              descriptor.navigate(true)
        }
    }

    /** Re-reads the config from Doppler, discarding unsaved local edits. */
    fun reload(file: DopplerSecretsFile) {
        runLoad(file.projectSlug, file.configName, "Reloading /${file.projectSlug}/${file.configName}…") {
            DopplerNotifier.info(project, "Reloaded /${file.projectSlug}/${file.configName} from Doppler.")
        }
    }

    private fun runLoad(
        projectSlug: String,
        configName: String,
        title: String,
        onReady: (DopplerSecretsFile) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private lateinit var snapshot: DopplerSecretsSnapshot
            private lateinit var text: String

            override fun run(indicator: ProgressIndicator) {
                snapshot = DopplerService.getInstance().loadSecrets(projectSlug, configName)
                text = SecretsDocumentFormat.serialize(snapshot.secrets)
            }

            override fun onSuccess() {
                invalidateCachedFiles(snapshot.authContextId)
                val file = DopplerFileSystem.getInstance().obtain(projectSlug, configName, snapshot.authContextId)
                if (file.pushInProgress.get()) {
                    DopplerNotifier.error(project, "Wait for the current sync before reloading /$projectSlug/$configName.")
                    return
                }
                onReady(materialize(projectSlug, configName, snapshot.authContextId, text, snapshot.secrets.associate { it.name to it.raw }))
            }

            override fun onThrowable(error: Throwable) {
                invalidateConfig(projectSlug, configName)
                DopplerNotifier.error(project, "Could not load /$projectSlug/$configName: ${error.message}")
            }
        })
    }

    private fun materialize(
        projectSlug: String,
        configName: String,
        authContextId: String,
        text: String,
        snapshot: Map<String, String?>,
    ): DopplerSecretsFile {
        val file = DopplerFileSystem.getInstance().obtain(projectSlug, configName, authContextId)
        applyServerContent(file, text, snapshot)
        return file
    }

    /** Keeps `${...}` completion in step with what the config actually contains on the server. */
    private fun publishSecretNames(file: DopplerSecretsFile) {
        SecretReferenceService.getInstance()
            .update(file.authContextId, file.projectSlug, file.configName, file.remoteSnapshot.keys)
    }

    /** Replaces both the file and its open document with server state, without pushing back. */
    private fun applyServerContent(file: DopplerSecretsFile, text: String, snapshot: Map<String, String?>) {
        if (file.isInvalidated) return
        file.suppressPush = true
        try {
            file.setServerContent(text, snapshot)
            publishSecretNames(file)
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
        if (project.isDisposed || openPaths.isEmpty()) return

        val interval = DopplerSettings.getInstance().refreshIntervalSeconds
        if (interval <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastPollAt < interval * 1000L) return
        lastPollAt = now

        val service = DopplerService.getInstance()
        val authContextId = try {
            service.currentAuthContextId()
        } catch (e: Exception) {
            thisLogger().debug("Could not verify the Doppler account while polling open configs", e)
            invalidateCachedFiles(null)
            return
        }
        invalidateCachedFiles(authContextId)
        if (WindowManager.getInstance().getFrame(project)?.isActive != true) return

        val fileSystem = DopplerFileSystem.getInstance()
        val documents = FileDocumentManager.getInstance()
        for (path in openPaths.toList()) {
            if (project.isDisposed) return
            val file = fileSystem.findFileByPath(path) as? DopplerSecretsFile ?: continue
            if (file.authContextId != authContextId) continue
            if (file.pushInProgress.get() || file.hasPendingSync) continue
            val document = documents.getCachedDocument(file)
            if (document != null && documents.isDocumentUnsaved(document)) continue
            refreshFromServer(file)
        }
    }

    private fun refreshFromServer(file: DopplerSecretsFile) {
        val snapshotBeforeFetch = file.remoteSnapshot
        val loaded = try {
            DopplerService.getInstance().loadSecrets(
                file.projectSlug,
                file.configName,
                expectedAuthContextId = file.authContextId,
            )
        } catch (e: Exception) {
            // Never keep a readable snapshot when its access cannot be revalidated.
            thisLogger().debug("Doppler auto-refresh failed for ${file.path}", e)
            invalidateCachedFile(file)
            return
        }

        val snapshot = loaded.secrets.associate { it.name to it.raw }
        if (snapshot == file.remoteSnapshot) return
        val text = SecretsDocumentFormat.serialize(loaded.secrets)

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || file.isInvalidated || !owns(file) || file.pushInProgress.get() || file.hasPendingSync) return@invokeLater
            if (file.remoteSnapshot != snapshotBeforeFetch) return@invokeLater
            val documents = FileDocumentManager.getInstance()
            val document = documents.getCachedDocument(file)
            // Never discard edits the user has not saved yet.
            if (document != null && documents.isDocumentUnsaved(document)) return@invokeLater
            applyServerContent(file, text, snapshot)
        }, project.disposed)
    }

    internal fun saveBeforeClose(file: DopplerSecretsFile, onSaved: () -> Unit): Boolean {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file) ?: return false
        closeAfterSync[file] = onSaved
        documents.saveDocument(document)
        if (file.hasPendingSync && !file.pushInProgress.get()) push(file, document.text)
        if (!file.hasPendingSync && !file.pushInProgress.get() && !documents.isDocumentUnsaved(document)) {
            closeAfterSync.remove(file)
            return true
        }
        if (!file.pushInProgress.get()) closeAfterSync.remove(file)
        return false
    }

    internal fun discardLocalChanges(file: DopplerSecretsFile) {
        file.pendingPushText.set(null)
        applyServerContent(file, SecretsDocumentFormat.serialize(file.remoteSnapshot), file.remoteSnapshot)
    }

    internal fun cancelCloseRequest(file: DopplerSecretsFile) {
        closeAfterSync.remove(file)
    }

    internal fun finishCloseRequest(file: DopplerSecretsFile) {
        val close = closeAfterSync.remove(file)
        if (!file.hasPendingSync && !project.isDisposed) close?.invoke()
    }

    /** Pushes a saved document to Doppler as a minimal set of writes. */
    fun push(file: DopplerSecretsFile, text: String) {
        if (file.isInvalidated) return
        file.hasPendingSync = true
        if (file.pushInProgress.get()) {
            file.pendingPushText.set(text)
            return
        }

        val edited = try {
            SecretsDocumentFormat.parse(text)
        } catch (e: SecretsFormatException) {
            DopplerNotifier.error(project, "Not saved to Doppler - ${e.message}")
            return
        }

        val changes = SecretChanges.between(file.remoteSnapshot, edited)
        if (changes.isEmpty) {
            file.hasPendingSync = false
            ApplicationManager.getApplication().invokeLater({ tabPresentation.refresh() }, project.disposed)
            return
        }

        if (!file.pushInProgress.compareAndSet(false, true)) {
            file.pendingPushText.set(text)
            return
        }

        val label = "/${file.projectSlug}/${file.configName}"
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Syncing $label to Doppler…", false) {
                private var refreshedText: String? = null
                private var refreshedSnapshot: Map<String, String?> = emptyMap()

                override fun run(indicator: ProgressIndicator) {
                    val service = DopplerService.getInstance()
                    try {
                        service.applyChanges(file.authContextId, file.projectSlug, file.configName, changes)
                    } catch (e: DopplerCliException) {
                        // Earlier operations may have succeeded; keep local edits, but rebase
                        // the next save on the actual server state when it can be read.
                        try {
                            refreshedSnapshot = service.loadSecrets(
                                file.projectSlug,
                                file.configName,
                                expectedAuthContextId = file.authContextId,
                            ).secrets
                                .associate { it.name to it.raw }
                        } catch (refreshError: DopplerCliException) {
                            thisLogger().warn("Could not refresh Doppler config after a failed sync ${file.path}", refreshError)
                        }
                        throw e
                    }
                    refreshedSnapshot = changes.applyTo(file.remoteSnapshot)
                    refreshedText = SecretsDocumentFormat.serialize(refreshedSnapshot)

                    // Re-read so the document shows exactly what Doppler now holds; otherwise a
                    // cleared line lingers, and the snapshot already matches so no later poll
                    // would ever correct it.
                    try {
                        val loaded = service.loadSecrets(
                            file.projectSlug,
                            file.configName,
                            expectedAuthContextId = file.authContextId,
                        )
                        refreshedSnapshot = loaded.secrets.associate { it.name to it.raw }
                        refreshedText = SecretsDocumentFormat.serialize(loaded.secrets)
                    } catch (e: DopplerCliException) {
                        thisLogger().warn("Could not refresh Doppler config after syncing ${file.path}", e)
                    }
                }

                override fun onSuccess() {
                    if (file.isInvalidated) return
                    val latestDocument = FileDocumentManager.getInstance().getCachedDocument(file)
                    val hasNewerEdits = file.pendingPushText.get() != null ||
                        (latestDocument != null &&
                            (latestDocument.text != text ||
                                FileDocumentManager.getInstance().isDocumentUnsaved(latestDocument)))
                    file.hasPendingSync = hasNewerEdits
                    refreshedText?.let {
                        file.remoteSnapshot = refreshedSnapshot
                        publishSecretNames(file)
                        if (!hasNewerEdits) applyServerContent(file, it, refreshedSnapshot)
                    }
                    DopplerNotifier.info(
                        project,
                        "Synced $label - ${changes.upserted.size} updated, ${changes.removed.size} deleted.",
                    )
                }

                override fun onThrowable(error: Throwable) {
                    closeAfterSync.remove(file)
                    if (error is DopplerCliException && error.kind in setOf(
                            DopplerCliException.Kind.NOT_AUTHENTICATED,
                            DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
                        )
                    ) {
                        invalidateCachedFiles(null)
                    }
                    DopplerNotifier.error(project, "Failed to sync $label: ${error.message}")
                }

                override fun onFinished() {
                    file.pushInProgress.set(false)
                    val pendingText = file.pendingPushText.getAndSet(null)
                    if (pendingText != null) {
                        push(file, pendingText)
                    } else {
                        tabPresentation.refresh()
                        finishCloseRequest(file)
                        if (!owns(file)) releaseIfUnused(file)
                    }
                }
            },
        )
    }

    private fun syncPendingConfig(file: DopplerSecretsFile) {
        if (!owns(file)) return
        val documents = FileDocumentManager.getInstance()
        if (!file.hasPendingSync || file.pushInProgress.get()) return
        val document = documents.getCachedDocument(file) ?: return
        push(file, document.text)
    }

    companion object {
        fun invalidateCachedFiles(currentAuthContextId: String?) {
            invalidateFiles { currentAuthContextId == null || it.authContextId != currentAuthContextId }
        }

        private fun invalidateCachedFile(file: DopplerSecretsFile) {
            invalidateFiles { it === file }
        }

        private fun invalidateConfig(projectSlug: String, configName: String) {
            invalidateFiles { it.projectSlug == projectSlug && it.configName == configName }
        }

        private fun invalidateFiles(shouldInvalidate: (DopplerSecretsFile) -> Boolean) {
            val fileSystem = DopplerFileSystem.getInstance()
            val staleFiles = fileSystem.cachedFiles().filter(shouldInvalidate)
            if (staleFiles.isEmpty()) return
            val application = ApplicationManager.getApplication()
            val invalidate = Runnable {
                for (file in staleFiles) {
                    file.suppressPush = true
                    try {
                        val documents = FileDocumentManager.getInstance()
                        val document = documents.getCachedDocument(file)
                        val owner = ProjectManager.getInstance().openProjects
                            .firstOrNull { !it.isDisposed && FileEditorManager.getInstance(it).isFileOpen(file) }
                        if (document != null) {
                            val clearDocument = {
                                document.setText(DopplerSecretsFile.EMPTY_DOCUMENT)
                                documents.saveDocument(document)
                            }
                            if (owner != null) {
                                WriteCommandAction.runWriteCommandAction(owner, "Clear Doppler secrets", null, clearDocument)
                            } else {
                                application.runWriteAction(clearDocument)
                            }
                        }
                        for (openProject in ProjectManager.getInstance().openProjects) {
                            openProject.getServiceIfCreated(DopplerSecretsEditorService::class.java)
                                ?.let { service ->
                                    service.openPaths.remove(file.path)
                                    service.closeAfterSync.remove(file)
                                }
                            FileEditorManager.getInstance(openProject).closeFile(file)
                        }
                        file.scrub()
                        fileSystem.forget(file)
                    } finally {
                        file.suppressPush = false
                    }
                }
            }
            if (application.isDispatchThread) {
                invalidate.run()
            } else {
                application.invokeAndWait(invalidate, ModalityState.any())
            }
        }

        /** Locates the project whose tool window opened [file], so cached files remain shared safely. */
        fun findOwner(file: DopplerSecretsFile): DopplerSecretsEditorService? =
            ProjectManager.getInstance().openProjects
                .asSequence()
                .filterNot { it.isDisposed }
                .mapNotNull { it.getServiceIfCreated(DopplerSecretsEditorService::class.java) }
                .firstOrNull { it.owns(file) }

        fun getInstance(project: Project): DopplerSecretsEditorService =
            project.getService(DopplerSecretsEditorService::class.java)

    }
}
