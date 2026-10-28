package com.vk.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.vk.doppler.cli.DopplerNameTransformer
import com.vk.doppler.injection.SecretNameMapping

/** Workspace preferences alone cannot enable injection; user-local approval is also required. */
@Service(Service.Level.PROJECT)
@State(name = "DopplerInjectionSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DopplerInjectionSettings(
    private val project: Project,
) : PersistentStateComponent<DopplerInjectionSettings.State> {
    data class State(
        var enabled: Boolean = false,
        var nameTransformer: DopplerNameTransformer = DopplerNameTransformer.NONE,
        var customMappings: MutableList<SecretNameMapping> = mutableListOf(),
    )

    private var settings = State()

    override fun getState(): State = settings

    override fun loadState(state: State) {
        settings = state.copy(customMappings = state.customMappings.mapTo(mutableListOf()) { it.copy() })
    }

    var enabled: Boolean
        get() = settings.enabled && DopplerInjectionConsent.getInstance().isGranted(project.basePath)
        set(value) {
            DopplerInjectionConsent.getInstance().setGranted(project.basePath, value)
            settings.enabled = value
        }

    var nameTransformer: DopplerNameTransformer
        get() = settings.nameTransformer
        set(value) {
            settings.nameTransformer = value
        }

    /** Kept while another transformer is selected, and used only while CUSTOM is selected. */
    var customMappings: List<SecretNameMapping>
        get() = settings.customMappings.map { it.copy() }
        set(value) {
            settings.customMappings = value.mapTo(mutableListOf()) { it.copy() }
        }

    companion object {
        fun getInstance(project: Project): DopplerInjectionSettings = project.service()
    }
}
