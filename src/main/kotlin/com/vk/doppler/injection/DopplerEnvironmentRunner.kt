package com.vk.doppler.injection

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.vk.doppler.settings.DopplerInjectionSettings

/** Delegate to the IDE's normal runner using an unregistered, execution-only profile copy. */
abstract class DopplerEnvironmentRunner : ProgramRunner<RunnerSettings> {
    protected abstract fun accepts(profile: RunProfile): Boolean
    protected abstract fun environment(profile: RunProfile): Map<String, String>
    protected abstract fun copyProfile(profile: RunProfile, values: Map<String, String>): RunConfiguration

    final override fun canRun(executorId: String, profile: RunProfile): Boolean =
        (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID) &&
            profile is RunConfiguration && !profile.project.isDisposed && accepts(profile) &&
            DopplerInjectionSettings.getInstance(profile.project).enabled && findDelegate(executorId, profile) != null

    private fun findDelegate(executorId: String, profile: RunProfile): ProgramRunner<*>? =
        ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull {
            it !is DopplerEnvironmentRunner && it.canRun(executorId, profile)
        }

    final override fun execute(environment: ExecutionEnvironment) {
        if (environment.project.isDisposed) throw ExecutionException("The project was closed before environment injection.")
        val original = environment.runProfile
        val delegate = findDelegate(environment.executor.id, original)
            ?: throw ExecutionException("No IDE runner is available for this Run/Debug configuration.")
        val existing = this.environment(original)
        val merged = DopplerInjectionService.getInstance(environment.project).mergeEnvironment(existing)
        val profile = if (merged === existing) original else copyProfile(original, merged)
        val builder = ExecutionEnvironmentBuilder(environment).runner(delegate)
        environment.runnerAndConfigurationSettings?.let { builder.runnerAndSettings(delegate, it) }
        builder.runProfile(profile).executionId(environment.executionId).dataContext(environment.dataContext)
        environment.modulePath?.let(builder::modulePath)
        val launch = builder.build(environment.callback)
        launch.isRunningCurrentFile = environment.isRunningCurrentFile
        if (environment.isHeadless) launch.setHeadless()
        delegate.execute(launch)
    }
}
