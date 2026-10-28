package com.vk.doppler.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vk.doppler.cli.DopplerCli
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerCliUpdate
import com.vk.doppler.cli.DopplerExecutableLocator
import com.vk.doppler.settings.DopplerSettings
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
internal class DopplerCliUpdateService {
    private val automaticCheckStarted = AtomicBoolean()
    private val busy = AtomicBoolean()
    private val updating = AtomicBoolean()

    internal fun claimAutomaticCheck(): Boolean =
        DopplerSettings.getInstance().checkForCliUpdates && automaticCheckStarted.compareAndSet(false, true)

    fun checkAutomatically(project: Project?) {
        if (claimAutomaticCheck()) check(project, automatic = true)
    }

    fun check(
        project: Project? = null,
        path: String = DopplerSettings.getInstance().cliPath,
        automatic: Boolean = false,
    ) {
        if (updating.get() || !busy.compareAndSet(false, true)) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Checking for Doppler CLI updates", false) {
                private var result: DopplerCliUpdate? = null
                private var executable: String? = null

                override fun run(indicator: ProgressIndicator) {
                    try {
                        executable = DopplerExecutableLocator.find(path)
                        result = executable?.let { DopplerCli(it).checkForUpdate() }
                    } catch (_: DopplerCliException) {
                        result = null
                    }
                }

                override fun onSuccess() {
                    if (project?.isDisposed == true) return
                    val update = result
                    if (update == null) {
                        if (!automatic) {
                            Messages.showErrorDialog(
                                "Could not check for Doppler CLI updates. Check the CLI path and network connection.",
                                "Doppler",
                            )
                        }
                    } else if (update.available) {
                        val notification =
                            NotificationGroupManager
                                .getInstance()
                                .getNotificationGroup("Doppler")
                                .createNotification(
                                    "Doppler CLI update available",
                                    "Installed: ${update.current}. Latest: ${update.latest}.",
                                    NotificationType.INFORMATION,
                                )
                        notification.addAction(
                            NotificationAction.createSimpleExpiring("Update CLI") {
                                confirmUpdate(project, requireNotNull(executable), update)
                            },
                        )
                        if (automatic) notification.notify(project) else confirmUpdate(project, requireNotNull(executable), update)
                    } else if (!automatic) {
                        Messages.showInfoMessage("Doppler CLI ${update.current} is up to date.", "Doppler")
                    }
                }

                override fun onFinished() {
                    busy.set(false)
                }
            },
        )
    }

    private fun confirmUpdate(
        project: Project?,
        executable: String,
        update: DopplerCliUpdate,
    ) {
        if (updating.get()) return
        if (Messages.showYesNoDialog(
                "Update Doppler CLI from ${update.current} to ${update.latest}? This runs doppler update for the selected executable.",
                "Doppler CLI Update",
                "Update",
                "Cancel",
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        if (!updating.compareAndSet(false, true)) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Updating Doppler CLI", false) {
                private var updated = false

                override fun run(indicator: ProgressIndicator) {
                    try {
                        val version = DopplerCli(executable).update()
                        updated =
                            Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+").matches(version) && !DopplerCliUpdate(version, update.latest).available
                    } catch (_: DopplerCliException) {
                        updated = false
                    }
                }

                override fun onSuccess() {
                    if (project?.isDisposed == true) return
                    if (updated) {
                        Messages.showInfoMessage("Doppler CLI updated successfully.", "Doppler")
                    } else {
                        Messages.showErrorDialog(
                            "Could not update Doppler CLI. Run doppler update in a terminal, or update it with the package manager used to install it. The plugin does not request administrator privileges.",
                            "Doppler",
                        )
                    }
                }

                override fun onFinished() {
                    updating.set(false)
                }
            },
        )
    }

    companion object {
        fun getInstance(): DopplerCliUpdateService = service()
    }
}
