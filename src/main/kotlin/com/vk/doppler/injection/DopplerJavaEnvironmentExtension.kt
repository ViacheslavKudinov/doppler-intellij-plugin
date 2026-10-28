package com.vk.doppler.injection

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings

/** Java's launch-parameter hook also covers Maven, which uses JavaRunConfigurationExtensionManager. */
class DopplerJavaEnvironmentExtension : RunConfigurationExtension() {
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        javaParameters: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        if (configuration.project.isDisposed) return
        javaParameters.env =
            DopplerInjectionService
                .getInstance(configuration.project)
                .mergeEnvironment(javaParameters.env.orEmpty())
    }
}
