package com.vk.doppler.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** User approval stays in this IDE's settings, outside project-controlled workspace files. */
@Service(Service.Level.APP)
@State(name = "DopplerInjectionConsent", storages = [Storage("doppler-injection-consent.xml", roamingType = RoamingType.DISABLED)])
class DopplerInjectionConsent : PersistentStateComponent<DopplerInjectionConsent.State> {
    data class State(
        var approvedProjects: MutableSet<String> = linkedSetOf(),
    )

    private var consent = State()

    @Synchronized
    override fun getState(): State = State(consent.approvedProjects.toMutableSet())

    @Synchronized
    override fun loadState(state: State) {
        consent = State(state.approvedProjects.filterTo(linkedSetOf()) { it.isNotBlank() })
    }

    @Synchronized
    internal fun isGranted(projectPath: String?): Boolean = projectPath != null && projectPath in consent.approvedProjects

    @Synchronized
    internal fun setGranted(
        projectPath: String?,
        granted: Boolean,
    ) {
        if (projectPath.isNullOrBlank()) return
        if (granted) consent.approvedProjects.add(projectPath) else consent.approvedProjects.remove(projectPath)
    }

    companion object {
        fun getInstance(): DopplerInjectionConsent = service()
    }
}
