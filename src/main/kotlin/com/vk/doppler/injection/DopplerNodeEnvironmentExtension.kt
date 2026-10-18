package com.vk.doppler.injection

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession

/** Node target launch sessions support both Run and Debug without changing the saved profile. */
class DopplerNodeEnvironmentExtension : AbstractNodeRunConfigurationExtension() {
    override fun isApplicableFor(configuration: AbstractNodeTargetRunProfile): Boolean = true

    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment,
    ): NodeRunConfigurationLaunchSession = object : NodeRunConfigurationLaunchSession() {
        override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
            if (configuration.project.isDisposed) return
            val merged = DopplerInjectionService.getInstance(configuration.project).mergeEnvironment(targetRun.envData.envs)
            targetRun.envData = targetRun.envData.with(merged)
        }
    }
}
