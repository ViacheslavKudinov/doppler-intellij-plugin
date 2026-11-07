package com.vk.doppler.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.service.DopplerService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

fun interface DopplerFolderSetupListener {
    fun changed()
}

/** A display cache only. The Doppler CLI owns persistent folder configuration. */
@Service(Service.Level.PROJECT)
class DopplerFolderSetupState(
    private val project: Project,
) : Disposable {
    var current: DopplerFolderSetup = DopplerFolderSetup()
        private set

    @Volatile var isSignedOut: Boolean = false
        private set
    private val revision = AtomicLong()
    private val authenticationRevision = AtomicLong()
    val authenticationGeneration: Long get() = authenticationRevision.get()

    fun isCurrentSession(generation: Long): Boolean = !isSignedOut && authenticationRevision.get() == generation

    @Volatile private var disposed = false
    private val pollHandle =
        AppExecutorUtil
            .getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::refreshFromCli, 30, 30, TimeUnit.SECONDS)

    /** Called on the EDT, including after applying settings. Invalidates pending reads. */
    fun update(setup: DopplerFolderSetup) {
        revision.incrementAndGet()
        if (disposed || project.isDisposed) return
        val changed = current != setup || isSignedOut
        isSignedOut = false
        if (!changed) return
        current = setup
        project.messageBus.syncPublisher(TOPIC).changed()
    }

    /** Authentication loss clears the display cache without changing CLI-owned folder defaults. */
    fun markSignedOut() {
        revision.incrementAndGet()
        if (disposed || project.isDisposed || isSignedOut) return
        isSignedOut = true
        authenticationRevision.incrementAndGet()
        current = DopplerFolderSetup()
        project.messageBus.syncPublisher(TOPIC).changed()
    }

    /** One local CLI read on a background thread; failures retain the last known setup. */
    internal fun refreshFromCli() {
        val folder = project.basePath ?: return
        if (disposed || project.isDisposed || isSignedOut) return
        // Newer reads supersede older queued results, even while the EDT is busy.
        val expectedRevision = revision.incrementAndGet()
        val setup =
            try {
                DopplerService.getInstance().folderSetup(folder, CliOperation.FOLDER_SETUP_POLL)
            } catch (_: DopplerCliException) {
                return
            }
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed && !isSignedOut && revision.get() == expectedRevision) update(setup)
        }, ModalityState.any())
    }

    override fun dispose() {
        disposed = true
        pollHandle.cancel(false)
    }

    companion object {
        val TOPIC = Topic.create("Doppler folder setup", DopplerFolderSetupListener::class.java)

        fun getInstance(project: Project): DopplerFolderSetupState = project.service()
    }
}
