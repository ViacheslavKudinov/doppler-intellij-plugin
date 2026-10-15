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

data class DopplerStatusReport(
    val status: DopplerStatus,
    val detail: String?,
    val authContextId: String? = null,
)

data class DopplerSecretsSnapshot(val authContextId: String, val secrets: List<DopplerSecret>)

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
        val authContextId = cli.authContextId()
        DopplerStatusReport(DopplerStatus.READY, cli.version(), authContextId)
    } catch (e: DopplerCliException) {
        val status = when (e.kind) {
            DopplerCliException.Kind.CLI_NOT_FOUND -> DopplerStatus.CLI_NOT_FOUND
            DopplerCliException.Kind.NOT_AUTHENTICATED -> DopplerStatus.NOT_AUTHENTICATED
            DopplerCliException.Kind.AUTH_CONTEXT_CHANGED -> DopplerStatus.ERROR
            DopplerCliException.Kind.GENERIC -> DopplerStatus.ERROR
        }
        DopplerStatusReport(status, e.message)
    }

    fun currentAuthContextId(): String = cli().authContextId()

    fun listProjects(expectedAuthContextId: String): List<DopplerProject> {
        val cli = cli()
        requireAuthContext(cli, expectedAuthContextId)
        val projects = cli.listProjects()
        requireAuthContext(cli, expectedAuthContextId)
        return projects
    }

    fun listConfigs(projectSlug: String): List<DopplerConfig> = cli().listConfigs(projectSlug)

    fun listConfigs(expectedAuthContextId: String, projectSlug: String): List<DopplerConfig> {
        val cli = cli()
        requireAuthContext(cli, expectedAuthContextId)
        val configs = cli.listConfigs(projectSlug)
        requireAuthContext(cli, expectedAuthContextId)
        return configs
    }

    fun listSecretNames(projectSlug: String, configName: String): Set<String> =
        cli().listSecretNames(projectSlug, configName)

    fun listSecretNames(expectedAuthContextId: String, projectSlug: String, configName: String): Set<String> {
        val cli = cli()
        requireAuthContext(cli, expectedAuthContextId)
        val names = cli.listSecretNames(projectSlug, configName)
        requireAuthContext(cli, expectedAuthContextId)
        return names
    }

    fun loadSecrets(
        projectSlug: String,
        configName: String,
        expectedAuthContextId: String? = null,
    ): DopplerSecretsSnapshot {
        val cli = cli()
        val authContextId = cli.authContextId()
        if (expectedAuthContextId != null && expectedAuthContextId != authContextId) {
            throw authContextChanged()
        }
        val secrets = cli.listSecrets(projectSlug, configName)
        requireAuthContext(cli, authContextId)
        return DopplerSecretsSnapshot(authContextId, secrets)
    }

    /**
     * Applies a diff to a config. Deletions run first so that a rename (delete + add)
     * cannot transiently exceed any per-config limits.
     */
    fun applyChanges(
        authContextId: String,
        projectSlug: String,
        configName: String,
        changes: SecretChanges,
    ) {
        val cli = cli()
        requireAuthContext(cli, authContextId)
        if (changes.removed.isNotEmpty()) {
            requireAuthContext(cli, authContextId)
            cli.deleteSecrets(projectSlug, configName, changes.removed)
        }
        for ((name, value) in changes.upserted) {
            requireAuthContext(cli, authContextId)
            cli.setSecret(projectSlug, configName, name, value)
        }
        requireAuthContext(cli, authContextId)
    }

    private fun requireAuthContext(cli: DopplerCli, expected: String) {
        if (cli.authContextId() != expected) throw authContextChanged()
    }

    private fun authContextChanged() = DopplerCliException(
        "The Doppler account changed. Reopen this config under the current account.",
        DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
    )

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
        fun unvaluedAdditions(original: Map<String, String?>, edited: ParsedSecrets): Set<String> =
            edited.values
                .filter { (name, value) -> name !in original && value == null }
                .keys + edited.cleared.filterNot(original::containsKey)

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
