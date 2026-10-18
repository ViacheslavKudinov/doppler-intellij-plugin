package com.vk.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.vk.doppler.cli.DopplerNameTransformer

/** Only preferences are persisted; no secret values or run configuration environments. */
@Service(Service.Level.PROJECT)
@State(name = "DopplerInjectionSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DopplerInjectionSettings : PersistentStateComponent<DopplerInjectionSettings.State> {
    data class State(var enabled: Boolean = false, var nameTransformer: DopplerNameTransformer = DopplerNameTransformer.NONE)
    private var settings = State()
    override fun getState(): State = settings
    override fun loadState(state: State) {
        settings = state.copy()
    }

    var enabled: Boolean
        get() = settings.enabled
        set(value) { settings.enabled = value }

    var nameTransformer: DopplerNameTransformer
        get() = settings.nameTransformer
        set(value) { settings.nameTransformer = value }

    companion object {
        fun getInstance(project: Project): DopplerInjectionSettings = project.service()
    }
}
