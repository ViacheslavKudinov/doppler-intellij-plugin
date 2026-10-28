package com.vk.doppler.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

/** Folder defaults belong to the current project; CLI options remain application settings. */
class DopplerFolderSetupConfigurable(
    private val project: Project,
) : BoundConfigurable("Folder Setup") {
    private var setupPanel: DopplerFolderSetupPanel? = null

    override fun createPanel(): DialogPanel =
        panel {
            val setup = DopplerFolderSetupPanel(project)
            setupPanel = setup
            row { cell(setup).align(AlignX.FILL) }
            onIsModified { setup.isModified() }
            onApply { setup.applySetup() }
            onReset { setup.resetSetup() }
        }

    override fun disposeUIResources() {
        setupPanel?.let { Disposer.dispose(it) }
        setupPanel = null
        super.disposeUIResources()
    }
}
