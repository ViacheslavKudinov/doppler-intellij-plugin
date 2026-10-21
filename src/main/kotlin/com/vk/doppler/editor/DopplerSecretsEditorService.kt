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
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.CliLogContext
import com.vk.doppler.completion.SecretReferenceService
import com.vk.doppler.notifications.DopplerNotifier
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerSecretsSnapshot
import com.vk.doppler.service.SecretChanges
import com.vk.doppler.settings.DopplerFolderSetupState
import com.vk.doppler.settings.DopplerSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val HEARTBEAT_SECONDS = 1L
private const val ACCOUNT_VALIDATION_INTERVAL_MS = 30_000L

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

    private var lastAccountValidationAt = 0L

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
        val fileEditorManager = FileEditorManager.getInstance(project)
        project.messageBus.connect(this).subscribe(
            DopplerFolderSetupState.TOPIC,
            DopplerFolderSetupTabIconUpdater(
                openFiles = { fileEditorManager.openFiles },
                updatePresentation = fileEditorManager::updateFilePresentation,
            ),
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
    fun openConfig(
        projectSlug: String,
        configName: String,
        environment: String? = null,
        treeAuthContextId: String? = null,
    ) {
        runLoad(
            projectSlug,
            configName,
            "Opening /$projectSlug/$configName…",
            environment,
            treeAuthContextId = treeAuthContextId,
        ) { file ->
            if (environment != null) file.environment = environment
            openPaths.add(file.path)
            val descriptor = OpenFileDescriptor(project, file)
            descriptor.isUsePreviewTab = true
            descriptor.navigate(true)
        }
    }

    /** Re-reads the config from Doppler, discarding unsaved local edits. */
    fun reload(file: DopplerSecretsFile) {
        runLoad(file.projectSlug, file.configName, "Reloading /${file.projectSlug}/${file.configName}…", file.environment, CliOperation.CONFIG_RELOAD) {
            DopplerNotifier.info(project, "Reloaded /${file.projectSlug}/${file.configName} from Doppler.")
        }
    }

    private fun runLoad(
        projectSlug: String,
        configName: String,
        title: String,
        environment: String?,
        operation: CliOperation = CliOperation.CONFIG_OPEN,
        treeAuthContextId: String? = null,
        onReady: (DopplerSecretsFile) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private lateinit var snapshot: DopplerSecretsSnapshot
            private lateinit var text: String

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Loading Doppler secrets for /$projectSlug/$configName…"
                indicator.text2 = "Waiting for the Doppler CLI to return secret values"
                val service = DopplerService.getInstance()
                snapshot = if (treeAuthContextId != null) {
                    service.loadSecretsAfterTreeValidation(
                        projectSlug,
                        configName,
                        treeAuthContextId,
                        operation,
                        environment,
                    )
                } else {
                    service.loadSecrets(projectSlug, configName, environment = environment, operation = operation)
                }
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
                if (error is DopplerCliException && error.kind in setOf(
                        DopplerCliException.Kind.NOT_AUTHENTICATED,
                        DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
                    )
                ) {
                    invalidateCachedFiles(null)
                } else {
                    invalidateConfig(projectSlug, configName)
                }
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

    /** Validates open configs' account independently of focused, clean value refreshes. */
    private fun pollOpenConfigs() {
        pollOpenConfigs(System.currentTimeMillis(), DopplerSettings.getInstance().refreshIntervalSeconds)
    }

    internal fun pollOpenConfigs(
        now: Long,
        interval: Int,
        refreshableFiles: () -> List<DopplerSecretsFile> = ::focusedRefreshFiles,
        refresh: (DopplerSecretsFile) -> Boolean = ::refreshFromServer,
        validateAccount: () -> String = {
            DopplerService.getInstance().currentAuthContextId(
                operation = CliOperation.ACCOUNT_VALIDATION,
                relatedConfigs = DopplerFileSystem.getInstance().cachedFiles().filter(::owns)
                    .map { CliLogContext(it.projectSlug, it.configName, it.environment) },
            )
        },
    ) {
        if (project.isDisposed || openPaths.isEmpty()) return

        var accountValidationRequired = false
        if (interval > 0 && now - lastPollAt >= interval * 1000L) {
            lastPollAt = now
            for (file in refreshableFiles().filter(::owns).sortedBy { it.path }) {
                if (project.isDisposed) return
                if (!owns(file) || file !in refreshableFiles()) continue
                if (refresh(file)) {
                    lastAccountValidationAt = now
                } else {
                    accountValidationRequired = true
                }
            }
        }

        if (openPaths.isEmpty() ||
            (!accountValidationRequired && now - lastAccountValidationAt < ACCOUNT_VALIDATION_INTERVAL_MS)) return
        lastAccountValidationAt = now
        try {
            invalidateCachedFiles(validateAccount())
        } catch (error: DopplerCliException) {
            // As with value refresh, unreadable authentication must not leave secrets visible.
            thisLogger().debug("Doppler account validation failed", error)
            invalidateCachedFiles(null)
        }
    }

    private fun focusedRefreshFiles(): List<DopplerSecretsFile> = autoRefreshFiles(
        WindowManager.getInstance().getFrame(project)?.isActive == true,
        FileEditorManager.getInstance(project).selectedFiles,
    )

    internal fun autoRefreshFiles(windowActive: Boolean, selectedFiles: Array<VirtualFile>): List<DopplerSecretsFile> =
        if (!windowActive) emptyList()
        else selectedFiles.filterIsInstance<DopplerSecretsFile>().filter(::canAutoRefresh)

    internal fun canAutoRefresh(file: DopplerSecretsFile): Boolean {
        if (file.isInvalidated || file.pushInProgress.get() || file.hasPendingSync ||
            file.pendingPushText.get() != null) return false
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return true
        // The IDE's unsaved flag can remain set after undo restores the server content.
        return document.text == SecretsDocumentFormat.serialize(file.remoteSnapshot)
    }

    internal fun refreshFromServer(file: DopplerSecretsFile): Boolean {
        val snapshotBeforeFetch = file.remoteSnapshot
        val loaded = try {
            DopplerService.getInstance().loadSecrets(
                file.projectSlug,
                file.configName,
                expectedAuthContextId = file.authContextId,
                operation = CliOperation.AUTO_REFRESH,
                environment = file.environment,
            )
        } catch (e: Exception) {
            // Never keep a readable snapshot when its access cannot be revalidated.
            thisLogger().debug("Doppler auto-refresh failed for ${file.path}", e)
            if (e is DopplerCliException && e.kind in setOf(
                    DopplerCliException.Kind.NOT_AUTHENTICATED,
                    DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
                )) {
                invalidateCachedFiles(null)
            } else {
                invalidateCachedFile(file)
            }
            return false
        }

        // loadSecrets validates the account before and after reading; no separate preflight is needed.
        invalidateCachedFiles(loaded.authContextId)
        val snapshot = loaded.secrets.associate { it.name to it.raw }
        if (snapshot == file.remoteSnapshot) return true
        val text = SecretsDocumentFormat.serialize(loaded.secrets)

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !owns(file) || file !in focusedRefreshFiles()) return@invokeLater
            if (file.remoteSnapshot != snapshotBeforeFetch) return@invokeLater
            applyServerContent(file, text, snapshot)
        }, project.disposed)
        return true
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

        if (SecretChanges.unvaluedAdditions(file.remoteSnapshot, edited).isNotEmpty()) {
            DopplerNotifier.error(
                project,
                "Not saved to Doppler - new secrets need a value. Use \"\" for an empty value; null is only a placeholder for an existing restricted secret.",
            )
            file.hasPendingSync = false
            return
        }

        val originalSnapshot = file.remoteSnapshot
        val changes = SecretChanges.between(originalSnapshot, edited)
        if (changes.isEmpty) {
            file.hasPendingSync = false
            ApplicationManager.getApplication().invokeLater({ tabPresentation.refresh() }, project.disposed)
            return
        }

        if (!file.pushInProgress.compareAndSet(false, true)) {
            file.pendingPushText.set(text)
            return
        }

        ProgressManager.getInstance().run(createPushTask(file, text, originalSnapshot, changes))
    }

    internal fun createPushTask(
        file: DopplerSecretsFile,
        text: String,
        originalSnapshot: Map<String, String?>,
        changes: SecretChanges,
        applyChanges: () -> Unit = {
            DopplerService.getInstance().applyChanges(
                file.authContextId, file.projectSlug, file.configName,
                originalSnapshot, changes, environment = file.environment,
            )
        },
    ): Task.Backgroundable {
        val label = "/${file.projectSlug}/${file.configName}"
        return object : Task.Backgroundable(project, "Syncing $label to Doppler…", false) {
            private var syncSucceeded = false
            private var refreshedText: String? = null
            private var refreshedSnapshot: Map<String, String?>? = null

            override fun run(indicator: ProgressIndicator) {
                applyChanges()
                refreshedSnapshot = changes.applyTo(originalSnapshot)
                refreshedText = SecretsDocumentFormat.serialize(requireNotNull(refreshedSnapshot))
            }

            override fun onSuccess() {
                if (file.isInvalidated) return
                syncSucceeded = true
                val latestDocument = FileDocumentManager.getInstance().getCachedDocument(file)
                val hasNewerEdits = file.pendingPushText.get() != null ||
                    (latestDocument != null &&
                        (latestDocument.text != text ||
                            FileDocumentManager.getInstance().isDocumentUnsaved(latestDocument)))
                file.hasPendingSync = hasNewerEdits
                refreshedText?.let {
                    val snapshot = requireNotNull(refreshedSnapshot)
                    file.remoteSnapshot = snapshot
                    publishSecretNames(file)
                    if (!hasNewerEdits) applyServerContent(file, it, snapshot)
                }
                DopplerNotifier.info(
                    project,
                    "Synced $label - ${changes.upserted.size} updated, ${changes.removed.size} deleted.",
                )
            }

            override fun onThrowable(error: Throwable) {
                closeAfterSync.remove(file)
                // The document still represents the original baseline. Rebasing it
                // silently could turn another user's additions into local deletions.
                file.hasPendingSync = true
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
                if (syncSucceeded && pendingText != null) {
                    push(file, pendingText)
                } else {
                    tabPresentation.refresh()
                    finishCloseRequest(file)
                    if (!owns(file)) releaseIfUnused(file)
                }
            }
        }
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
