package com.vk.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Persisted plugin settings. Only non-sensitive data is stored here — authentication is
 * owned entirely by the Doppler CLI (`doppler login`), so no tokens ever reach this file.
 */
@Service(Service.Level.APP)
@State(name = "DopplerSettings", storages = [Storage("doppler.xml")])
class DopplerSettings : PersistentStateComponent<DopplerSettings.State> {

    data class State(
        var cliPath: String = "",
        var refreshIntervalSeconds: Int = 5,
        var logCliCalls: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var cliPath: String
        get() = state.cliPath
        set(value) {
            state.cliPath = value
        }

    var logCliCalls: Boolean
        get() = state.logCliCalls
        set(value) { state.logCliCalls = value }

    /** How often open configs are re-read from Doppler. `0` disables auto-refresh. */
    var refreshIntervalSeconds: Int
        get() = state.refreshIntervalSeconds
        set(value) {
            state.refreshIntervalSeconds = value
        }

    companion object {
        fun getInstance(): DopplerSettings = service()
    }
}
