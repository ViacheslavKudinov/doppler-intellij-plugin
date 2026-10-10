package com.vk.doppler.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.vk.doppler.cli.DopplerCli
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerExecutableLocator
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.cli.DopplerSecret
import com.vk.doppler.editor.ParsedSecrets
import com.vk.doppler.settings.DopplerSettings

enum class DopplerStatus { READY, CLI_NOT_FOUND, NOT_AUTHENTICATED, ERROR }

data class DopplerStatusReport(val status: DopplerStatus, val detail: String?)

/**
 * Entry point for all Doppler access. Every method performs blocking I/O and must be
 * called off the EDT.
 */
@Service(Service.Level.APP)
class DopplerService {

    private fun cli(): DopplerCli {
        val path = DopplerExecutableLocator.find(DopplerSettings.getInstance().cliPath)
            ?: throw DopplerCliException(
                "The Doppler CLI could not be found. Install it from https://docs.doppler.com/docs/install-cli " +
                    "or set its path in Settings | Tools | Doppler.",
                DopplerCliException.Kind.CLI_NOT_FOUND,
            )
        return DopplerCli(path)
    }

    fun checkStatus(): DopplerStatusReport = try {
        val cli = cli()
        cli.checkAuth()
        DopplerStatusReport(DopplerStatus.READY, cli.version())
    } catch (e: DopplerCliException) {
        val status = when (e.kind) {
            DopplerCliException.Kind.CLI_NOT_FOUND -> DopplerStatus.CLI_NOT_FOUND
            DopplerCliException.Kind.NOT_AUTHENTICATED -> DopplerStatus.NOT_AUTHENTICATED
            DopplerCliException.Kind.GENERIC -> DopplerStatus.ERROR
        }
        DopplerStatusReport(status, e.message)
    }

    fun listProjects(): List<DopplerProject> = cli().listProjects()

    fun listConfigs(projectSlug: String): List<DopplerConfig> = cli().listConfigs(projectSlug)

    fun listSecrets(projectSlug: String, configName: String): List<DopplerSecret> =
        cli().listSecrets(projectSlug, configName)

    /**
     * Applies a diff to a config. Deletions run first so that a rename (delete + add)
     * cannot transiently exceed any per-config limits.
     */
    fun applyChanges(projectSlug: String, configName: String, changes: SecretChanges) {
        val cli = cli()
        cli.deleteSecrets(projectSlug, configName, changes.removed)
        for ((name, value) in changes.upserted) {
            cli.setSecret(projectSlug, configName, name, value)
        }
    }

    companion object {
        fun getInstance(): DopplerService = service()
    }
}

/** The minimal set of writes needed to turn the remote config into the edited document. */
data class SecretChanges(
    val upserted: Map<String, String>,
    val removed: Set<String>,
) {
    val isEmpty: Boolean get() = upserted.isEmpty() && removed.isEmpty()
    val totalOperations: Int get() = upserted.size + removed.size

    fun applyTo(original: Map<String, String?>): Map<String, String?> =
        (original - removed) + upserted

    companion object {
        fun between(original: Map<String, String?>, edited: ParsedSecrets): SecretChanges {
            val upserted = edited.values
                .mapNotNull { (name, value) -> value?.let { name to it } }
                .filter { (name, value) -> original[name] != value }
                .toMap()

            // Emptying a value deletes the secret, including a restricted one. A value left as
            // the literal `null` is the unreadable placeholder and must stay a no-op.
            val removed = (original.keys - edited.values.keys - edited.cleared) +
                edited.cleared.filter { original.containsKey(it) }

            return SecretChanges(upserted, removed)
        }
    }
}
