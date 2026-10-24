package com.vk.doppler.injection

import com.intellij.execution.ExecutionException
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerNameTransformer
import com.vk.doppler.service.DopplerService
import com.vk.doppler.settings.DopplerInjectionSettings

/** Fetches fresh secrets per launch without retaining them or modifying a stored RunConfiguration. */
@Service(Service.Level.PROJECT)
class DopplerInjectionService(private val project: Project) {
    internal fun mergeEnvironment(existing: Map<String, String>): Map<String, String> {
        if (project.isDisposed || !TrustedProjects.isProjectTrusted(project)) {
            return existing
        }
        val settings = DopplerInjectionSettings.getInstance(project)
        if (!settings.enabled) {
            return existing
        }
        // Some launch hooks run on the EDT. Keep CLI I/O on a pooled thread with cancellable progress.
        if (ApplicationManager.getApplication().isDispatchThread) {
            var result: Map<String, String> = existing
            var failure: ExecutionException? = null
            val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously({
                try { result = loadAndMerge(existing) } catch (error: ExecutionException) { failure = error }
            }, "Loading Doppler environment", true, project)
            if (!completed) throw ExecutionException("Doppler environment injection was cancelled.")
            failure?.let { throw it }
            return result
        }
        return loadAndMerge(existing)
    }

    private fun loadAndMerge(existing: Map<String, String>): Map<String, String> {
        try {
            val folder = project.basePath ?: return existing
            val service = DopplerService.getInstance()
            // Local preflight: an unconfigured project needs no login or secret access.
            val setup = service.folderSetup(folder, CliOperation.RUN_INJECTION)
            val slug = setup.projectSlug
            val config = setup.configName
            if (slug.isNullOrBlank() || config.isNullOrBlank()) {
                return existing
            }
            // Re-read defaults inside the account checks so the preflight cannot become a stale snapshot.
            val account = service.injectionAccount(folder)
            val validatedSetup = service.folderSetup(folder, CliOperation.RUN_INJECTION)
            if (validatedSetup.projectSlug != slug || validatedSetup.configName != config) {
                throw DopplerCliException("Doppler account or folder setup changed during environment loading.")
            }
            val settings = DopplerInjectionSettings.getInstance(project)
            val secrets = service.downloadEnvironment(folder, slug, config, settings.nameTransformer)
            val currentSetup = service.folderSetup(folder, CliOperation.RUN_INJECTION)
            if (service.injectionAccount(folder) != account ||
                currentSetup.projectSlug != slug || currentSetup.configName != config
            ) {
                throw DopplerCliException("Doppler account or folder setup changed during environment loading.")
            }
            if (project.isDisposed || !settings.enabled || !TrustedProjects.isProjectTrusted(project)) {
                return existing
            }
            val named = applyConfiguredNameMapping(secrets, settings)
            return mergeInjectionEnvironment(existing, named)
        } catch (_: DopplerCliException) {
            // CLI output and arbitrary causes can contain secrets; do not attach them to an IDE exception.
            throw ExecutionException("Could not load the Doppler environment. Check CLI login and Settings | Tools | Doppler | Folder Setup.")
        }
    }

    private fun applyConfiguredNameMapping(
        secrets: Map<String, String>,
        settings: DopplerInjectionSettings,
    ): Map<String, String> {
        if (settings.nameTransformer != DopplerNameTransformer.CUSTOM) {
            return secrets
        }
        val mappings = settings.customMappings
        if (validateSecretNameMappings(mappings) != null) {
            throw ExecutionException("Custom Doppler name mappings are invalid. Check Settings | Tools | Doppler | Run and Debug.")
        }
        val named = applySecretNameMapping(secrets, mappings)
        if (named.size != secrets.size) {
            throw ExecutionException("Custom Doppler name mappings conflict with existing secret names. Choose unique new names in Settings | Tools | Doppler | Run and Debug.")
        }
        return named
    }

    companion object {
        fun getInstance(project: Project): DopplerInjectionService = project.service()
    }
}

/** Explicit run configuration values win; neither input map is modified. */
internal fun mergeInjectionEnvironment(existing: Map<String, String>, secrets: Map<String, String>): Map<String, String> =
    HashMap(secrets).apply { putAll(existing) }
