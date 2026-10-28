package com.vk.doppler.injection

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.jetbrains.python.run.AbstractPythonRunConfiguration

/** Python's normal Run/Debug runners read the copied profile on both command-line and target paths. */
class DopplerPythonEnvironmentRunner : DopplerEnvironmentRunner() {
    override fun getRunnerId(): String = "DopplerPythonEnvironment"

    override fun accepts(profile: RunProfile): Boolean = profile is AbstractPythonRunConfiguration<*>

    override fun environment(profile: RunProfile): Map<String, String> = (profile as AbstractPythonRunConfiguration<*>).envs

    override fun copyProfile(
        profile: RunProfile,
        values: Map<String, String>,
    ): RunConfiguration =
        ((profile as AbstractPythonRunConfiguration<*>).clone() as AbstractPythonRunConfiguration<*>).apply {
            envs = HashMap(values)
        }
}
