package com.vk.doppler.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.AsyncProcessIcon
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.service.DopplerService
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/** Edits CLI-owned folder defaults; values are written only when Settings is applied. */
internal class DopplerFolderSetupPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val folder = project.basePath
    internal val projectChoice = ComboBox<String>()
    internal val configChoice = ComboBox<String>()
    internal val status = JLabel("Loading folder setup…")
    internal val loadingIndicator =
        AsyncProcessIcon("Loading Doppler folder setup").apply {
            suspend()
            isVisible = false
        }
    private val normalStatusForeground = status.foreground
    private var validationBalloon: Balloon? = null
    internal val validationMessageTimer = Timer(5_000) { clearValidationMessage() }.apply { isRepeats = false }
    private val reload = JButton("Reload")
    internal val clear = JButton("Clear folder setup")
    private var baseline = DopplerFolderSetup()
    private var loadedAuthContextId: String? = null
    private var accountInvalidated = false
    private var loaded = false
    private var started = false
    private var busy = false
    private var updating = false
    private var clearPending = false
    private var disposed = false
    private var generation = 0

    init {
        Disposer.register(this, loadingIndicator)
        add(
            panel {
                row("Folder:") { label(folder ?: "Open a project with a local folder to configure Doppler setup.") }
                row("Project:") { cell(projectChoice).align(AlignX.FILL) }
                row("Config:") { cell(configChoice).align(AlignX.FILL) }
                row {
                    cell(reload)
                    cell(clear)
                }
                row {
                    cell(loadingIndicator)
                    cell(status)
                }
                row {
                    comment(
                        "Choose the Doppler project and config for this folder, as with doppler setup. " +
                            "Changes are saved when you click Apply or OK. Clearing removes this folder's override; " +
                            "defaults from a parent folder may still apply.",
                    )
                }
            },
            BorderLayout.CENTER,
        )
        projectChoice.addActionListener {
            if (!updating) {
                clearValidationMessage()
                clearPending = false
                loadConfigs(projectChoice.selectedItem as? String)
            }
        }
        configChoice.addActionListener {
            if (!updating) {
                clearValidationMessage()
                clearPending = false
            }
        }
        reload.addActionListener { load() }
        clear.addActionListener {
            clearValidationMessage()
            updating = true
            projectChoice.selectedItem = null
            configChoice.model = DefaultComboBoxModel()
            updating = false
            clearPending = baseline != DopplerFolderSetup()
            status.text =
                if (clearPending) {
                    "This folder's setup will be cleared when you apply settings."
                } else {
                    "No folder setup. Choose a project and config."
                }
        }
        project.messageBus.connect(this).subscribe(
            DopplerFolderSetupState.TOPIC,
            DopplerFolderSetupListener {
                if (loaded && !busy && !accountInvalidated && !isModified()) {
                    baseline = DopplerFolderSetupState.getInstance(project).current
                    resetSetup()
                    status.text = "Folder setup changed in the CLI."
                }
            },
        )
        enableControls(true)
        if (folder == null) status.text = "Folder setup is unavailable."
    }

    override fun addNotify() {
        super.addNotify()
        if (!started && folder != null) {
            started = true
            load()
        }
    }

    override fun dispose() {
        disposed = true
        generation++
        loadingIndicator.suspend()
        loadingIndicator.isVisible = false
        clearValidationMessage()
    }

    private fun showValidationMessage() {
        clearValidationMessage()
        status.text = INCOMPLETE_SETUP_MESSAGE
        status.icon = AllIcons.General.Error
        status.foreground = JBColor.RED
        val missingChoice = if ((projectChoice.selectedItem as? String).isNullOrBlank()) projectChoice else configChoice
        missingChoice.putClientProperty(OUTLINE_PROPERTY, "error")
        missingChoice.requestFocusInWindow()
        if (missingChoice.isShowing) {
            validationBalloon =
                JBPopupFactory
                    .getInstance()
                    .createHtmlTextBalloonBuilder(INCOMPLETE_SETUP_MESSAGE, MessageType.ERROR, null)
                    .setFadeoutTime(5_000)
                    .setHideOnAction(false)
                    .createBalloon()
                    .also { balloon ->
                        balloon.show(RelativePoint.getSouthWestOf(missingChoice), Balloon.Position.below)
                    }
        }
        validationMessageTimer.restart()
    }

    private fun clearValidationMessage() {
        validationMessageTimer.stop()
        validationBalloon?.hide()
        validationBalloon = null
        projectChoice.putClientProperty(OUTLINE_PROPERTY, null)
        configChoice.putClientProperty(OUTLINE_PROPERTY, null)
        status.icon = null
        status.foreground = normalStatusForeground
        if (status.text == INCOMPLETE_SETUP_MESSAGE) status.text = "Choose a config, then apply settings."
    }

    fun isModified(): Boolean = loaded && selection() != baseline

    fun resetSetup() {
        if (!loaded) return
        clearValidationMessage()
        clearPending = false
        updating = true
        projectChoice.selectedItem = baseline.projectSlug
        configChoice.model = DefaultComboBoxModel(listOfNotNull(baseline.configName).toTypedArray())
        configChoice.selectedItem = baseline.configName
        updating = false
        loadConfigs(baseline.projectSlug, baseline.configName)
    }

    fun applySetup() {
        if (!isModified()) return
        if (busy) throw ConfigurationException("Wait for Doppler configs to finish loading.")
        if (accountInvalidated) throw ConfigurationException(ACCOUNT_CHANGED_MESSAGE)
        val selected = selection()
        if (!clearPending && (selected.projectSlug.isNullOrBlank() || selected.configName.isNullOrBlank())) {
            showValidationMessage()
            // Abort Apply/OK without handing the IDE a persistent Settings error banner.
            throw ProcessCanceledException()
        }
        val expectedAccount = loadedAuthContextId ?: throw ConfigurationException("Reload Doppler folder setup before applying changes.")
        val scope = folder ?: return
        var failure: Exception? = null
        var effective = selected
        val completed =
            ProgressManager.getInstance().runProcessWithProgressSynchronously({
                try {
                    val service = DopplerService.getInstance()
                    if (clearPending) {
                        service.clearFolderSetup(scope, expectedAccount)
                    } else {
                        service.setFolderSetup(
                            scope,
                            requireNotNull(selected.projectSlug),
                            requireNotNull(selected.configName),
                            expectedAccount,
                        )
                    }
                    effective = service.folderSetup(scope)
                } catch (error: Exception) {
                    failure = error
                }
            }, "Saving Doppler folder setup…", false, project)
        failure?.let {
            if (it is DopplerCliException && it.kind == DopplerCliException.Kind.AUTH_CONTEXT_CHANGED) {
                invalidateAccount()
                throw ConfigurationException(ACCOUNT_CHANGED_MESSAGE)
            }
            throw ConfigurationException(it.message ?: "Could not save Doppler folder setup.")
        }
        if (!completed) throw ConfigurationException("Doppler folder setup was not saved.")
        baseline = effective
        busy = true
        DopplerFolderSetupState.getInstance(project).update(effective)
        busy = false
        resetSetup()
        status.text = "Folder setup saved."
    }

    private fun selection() =
        DopplerFolderSetup(
            projectChoice.selectedItem as? String,
            configChoice.selectedItem as? String,
        )

    private fun load() {
        val scope = folder ?: return
        background("Loading Doppler folder setup…", {
            DopplerService.getInstance().loadFolderSetup(scope)
        }) { snapshot ->
            val setup = snapshot.setup
            val projects = snapshot.projects.map { it.slug }
            val configs = snapshot.configs.map { it.name }
            loadedAuthContextId = snapshot.authContextId
            accountInvalidated = false
            baseline = setup
            loaded = true
            clearPending = false
            updating = true
            projectChoice.model = DefaultComboBoxModel((projects + listOfNotNull(setup.projectSlug)).distinct().toTypedArray())
            projectChoice.selectedItem = setup.projectSlug
            configChoice.model = DefaultComboBoxModel((configs + listOfNotNull(setup.configName)).distinct().toTypedArray())
            configChoice.selectedItem = setup.configName
            updating = false
            DopplerFolderSetupState.getInstance(project).update(setup)
            status.text =
                if (setup.projectSlug ==
                    null
                ) {
                    "No folder setup. Choose a project and config."
                } else {
                    "Current CLI setup (may be inherited from a parent folder)."
                }
        }
    }

    private fun loadConfigs(
        slug: String?,
        selected: String? = null,
    ) {
        updating = true
        configChoice.model = DefaultComboBoxModel(listOfNotNull(selected).toTypedArray())
        configChoice.selectedItem = selected
        updating = false
        if (slug == null || accountInvalidated) return
        val expectedAccount = loadedAuthContextId ?: return
        background("Loading Doppler configs…", {
            DopplerService.getInstance().folderSetupConfigs(expectedAccount, slug).map { it.name }
        }) { names ->
            updating = true
            configChoice.model = DefaultComboBoxModel((names + listOfNotNull(selected)).distinct().toTypedArray())
            configChoice.selectedItem = selected
            updating = false
            status.text = "Choose a config, then apply settings."
        }
    }

    private fun enableControls(enabled: Boolean) {
        projectChoice.isEnabled = enabled && loaded && !accountInvalidated
        configChoice.isEnabled = enabled && loaded && !accountInvalidated
        clear.isEnabled = enabled && loaded && !accountInvalidated
        reload.isEnabled = enabled && folder != null
    }

    private fun invalidateAccount() {
        accountInvalidated = true
        status.text = ACCOUNT_CHANGED_MESSAGE
        enableControls(!busy)
    }

    private companion object {
        const val OUTLINE_PROPERTY = "JComponent.outline"
        const val ACCOUNT_CHANGED_MESSAGE = "The Doppler account changed. Reload folder setup before applying changes."
        const val INCOMPLETE_SETUP_MESSAGE = "Choose both a Doppler project and config, or clear the folder setup."
    }

    private fun <T> background(
        title: String,
        work: () -> T,
        success: (T) -> Unit,
    ) {
        clearValidationMessage()
        val request = ++generation
        busy = true
        enableControls(false)
        status.text = title
        loadingIndicator.isVisible = true
        loadingIndicator.resume()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, false) {
                private var result: T? = null

                override fun run(indicator: ProgressIndicator) {
                    result = work()
                }

                override fun onSuccess() {
                    if (disposed || project.isDisposed || request != generation) return
                    @Suppress("UNCHECKED_CAST")
                    success(result as T)
                }

                override fun onThrowable(error: Throwable) {
                    if (!disposed && !project.isDisposed && request == generation) {
                        if (error is DopplerCliException && error.kind == DopplerCliException.Kind.AUTH_CONTEXT_CHANGED) {
                            invalidateAccount()
                        } else {
                            status.text = "Could not load Doppler setup. Check the CLI path and login, then Reload."
                        }
                    }
                }

                override fun onFinished() {
                    if (!disposed && !project.isDisposed && request == generation) {
                        busy = false
                        loadingIndicator.suspend()
                        loadingIndicator.isVisible = false
                        enableControls(true)
                    }
                }
            },
        )
    }
}
