package com.vk.doppler.injection

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemExecutionAware.Companion.getEnvironmentConfigurationProvider
import org.gradle.tooling.LongRunningOperation
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.service.execution.LocalGradleExecutionAware
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension

/** Patch the Tooling API operation, after Gradle has set up its environment; never mutate saved settings. */
class DopplerGradleEnvironmentExtension : GradleExecutionHelperExtension {
    override fun configureOperation(operation: LongRunningOperation, context: GradleExecutionContext) {
        if (context.taskId.type != ExternalSystemTaskType.EXECUTE_TASK) return
        if (context.project.isDisposed) return
        val settings = context.settings
        try {
            val configured = settings.env
            val merged = DopplerInjectionService.getInstance(context.project).mergeEnvironment(configured)
            if (merged === configured) return
            val target = settings.getEnvironmentConfigurationProvider()?.environmentConfiguration
            if (target != null && target.typeId != LocalGradleExecutionAware.LOCAL_TARGET_TYPE_ID) {
                operation.setEnvironmentVariables(merged)
            } else {
                val parent = if (settings.isPassParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                    else GeneralCommandLine.ParentEnvironmentType.NONE
                operation.setEnvironmentVariables(GeneralCommandLine().withEnvironment(merged).withParentEnvironmentType(parent).effectiveEnvironment)
            }
        } catch (error: ExecutionException) {
            // Gradle invokes extensions with forEachExtensionSafe. Cancel the operation as well,
            // so swallowing this sanitized error cannot launch a task without its secrets.
            operation.withCancellationToken { true }
            throw error
        }
    }
}
