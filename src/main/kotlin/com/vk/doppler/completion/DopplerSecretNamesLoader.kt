package com.vk.doppler.completion

import com.intellij.openapi.diagnostic.Logger
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.service.DopplerService
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService

/** The slice of Doppler access needed to list secret names. */
internal interface DopplerNamesApi {
    fun listProjects(authContextId: String): List<DopplerProject>
    fun listConfigs(authContextId: String, projectSlug: String): List<DopplerConfig>
    fun listSecretNames(authContextId: String, projectSlug: String, configName: String): Set<String>
    fun currentAuthContextId(): String
}

internal object DopplerServiceNamesApi : DopplerNamesApi {
    override fun listProjects(authContextId: String): List<DopplerProject> =
        DopplerService.getInstance().listProjects(authContextId)

    override fun listConfigs(authContextId: String, projectSlug: String): List<DopplerConfig> =
        DopplerService.getInstance().listConfigs(authContextId, projectSlug)

    override fun listSecretNames(authContextId: String, projectSlug: String, configName: String): Set<String> =
        DopplerService.getInstance().listSecretNames(authContextId, projectSlug, configName)

    override fun currentAuthContextId(): String = DopplerService.getInstance().currentAuthContextId()
}

/**
 * Reads secret names for the whole account: one project per pool task, and a single account
 * check before and after instead of one around every config.
 *
 * A project or config that cannot be read is skipped so that one restricted config does not
 * disable completion for everything else.
 */
internal class DopplerSecretNamesLoader(
    private val api: DopplerNamesApi,
    private val pool: ExecutorService,
) : SecretNamesLoader {

    override fun load(
        authContextId: String,
        checkCancelled: () -> Unit,
        reportProgress: (String) -> Unit,
        previousNames: SecretNames,
    ): SecretNames {
        reportProgress("Listing Doppler projects…")
        val projects = api.listProjects(authContextId)
        val tasks = projects.map { project ->
            project.slug to pool.submit(
                Callable {
                    loadProject(project.slug, authContextId, previousNames[project.slug].orEmpty(), checkCancelled, reportProgress)
                },
            )
        }
        val result = LinkedHashMap<String, Map<String, Set<String>>>()
        try {
            for ((slug, task) in tasks) {
                val configs = try {
                    task.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                result[slug] = configs
            }
        } finally {
            tasks.forEach { (_, task) -> task.cancel(true) }
        }
        if (api.currentAuthContextId() != authContextId) {
            throw DopplerCliException(
                "The Doppler account changed while loading secret names.",
                DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
            )
        }
        return result
    }

    private fun loadProject(
        slug: String,
        authContextId: String,
        previousNames: Map<String, Set<String>>,
        checkCancelled: () -> Unit,
        reportProgress: (String) -> Unit,
    ): Map<String, Set<String>> {
        reportProgress("Listing configs for $slug…")
        checkCancelled()
        val configs = try {
            api.listConfigs(authContextId, slug)
        } catch (e: DopplerCliException) {
            skipOrFail(e, "configs of $slug")
            return previousNames
        }
        val names = LinkedHashMap<String, Set<String>>()
        for (config in configs) {
            checkCancelled()
            reportProgress("Loading secret names for $slug/${config.name}…")
            try {
                names[config.name] = api.listSecretNames(authContextId, slug, config.name)
            } catch (e: DopplerCliException) {
                skipOrFail(e, "secrets of $slug/${config.name}")
                previousNames[config.name]?.let { names[config.name] = it }
            }
        }
        return names
    }

    private fun skipOrFail(error: DopplerCliException, what: String) {
        if (error.kind == DopplerCliException.Kind.CLI_NOT_FOUND ||
            error.kind == DopplerCliException.Kind.AUTH_CONTEXT_CHANGED
        ) {
            throw error
        }
        LOG.info("Skipping $what for completion: ${error.message}")
    }

    private companion object {
        val LOG = Logger.getInstance(DopplerSecretNamesLoader::class.java)
    }
}
