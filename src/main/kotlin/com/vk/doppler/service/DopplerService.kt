package com.vk.doppler.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.vk.doppler.cli.CliLogContext
import com.vk.doppler.cli.CliOperation
import com.vk.doppler.cli.DopplerAccountInfo
import com.vk.doppler.cli.DopplerCli
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerExecutableLocator
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.cli.DopplerNameTransformer
import com.vk.doppler.cli.DopplerProject
import com.vk.doppler.cli.DopplerSecret
import com.vk.doppler.editor.ParsedSecrets
import com.vk.doppler.settings.DopplerSettings

enum class DopplerStatus { READY, CLI_NOT_FOUND, NOT_AUTHENTICATED, ERROR }

data class DopplerStatusReport(
    val status: DopplerStatus,
    val detail: String?,
    val authContextId: String? = null,
    val account: DopplerAccountInfo? = null,
)

data class DopplerSecretsSnapshot(
    val authContextId: String,
    val secrets: List<DopplerSecret>,
)

data class DopplerFolderSetupSnapshot(
    val authContextId: String,
    val setup: DopplerFolderSetup,
    val projects: List<DopplerProject>,
    val configs: List<DopplerConfig>,
)

/**
 * Entry point for all Doppler access. Every method performs blocking I/O and must be
 * called off the EDT.
 */
@Service(Service.Level.APP)
class DopplerService {
    private fun cli(
        operation: CliOperation = CliOperation.AUTOCOMPLETE,
        context: CliLogContext = CliLogContext(),
        relatedConfigs: List<CliLogContext> = emptyList(),
        scope: String? = null,
    ): DopplerCli {
        val path =
            DopplerExecutableLocator.find(DopplerSettings.getInstance().cliPath)
                ?: throw DopplerCliException(
                    "The Doppler CLI could not be found. Install it from https://docs.doppler.com/docs/install-cli " +
                        "or set its path in Settings | Tools | Doppler.",
                    DopplerCliException.Kind.CLI_NOT_FOUND,
                )
        return DopplerCli(path, operation, context, relatedConfigs, scope)
    }

    fun checkStatus(
        includeVersion: Boolean = true,
        operation: CliOperation = if (includeVersion) CliOperation.TEST_CONNECTION else CliOperation.TREE_RELOAD,
    ): DopplerStatusReport =
        try {
            val cli = cli(operation)
            val account = cli.accountInfo()
            DopplerStatusReport(DopplerStatus.READY, if (includeVersion) cli.version() else null, account.authContextId, account)
        } catch (e: DopplerCliException) {
            val status =
                when (e.kind) {
                    DopplerCliException.Kind.CLI_NOT_FOUND -> DopplerStatus.CLI_NOT_FOUND
                    DopplerCliException.Kind.NOT_AUTHENTICATED -> DopplerStatus.NOT_AUTHENTICATED
                    DopplerCliException.Kind.AUTH_CONTEXT_CHANGED -> DopplerStatus.ERROR
                    DopplerCliException.Kind.REMOTE_CHANGED -> DopplerStatus.ERROR
                    DopplerCliException.Kind.GENERIC -> DopplerStatus.ERROR
                }
            DopplerStatusReport(status, e.message)
        }

    fun currentAuthContextId(
        operation: CliOperation = CliOperation.AUTOCOMPLETE,
        relatedConfigs: List<CliLogContext> = emptyList(),
    ): String = cli(operation, relatedConfigs = relatedConfigs).authContextId()

    /** Tree reload already checked the account through checkStatus; avoid repeating that request. */
    internal fun listTreeProjects(operation: CliOperation = CliOperation.TREE_RELOAD): List<DopplerProject> = cli(operation).listProjects()

    fun listProjects(expectedAuthContextId: String): List<DopplerProject> {
        val cli = cli()
        requireAuthContext(cli, expectedAuthContextId)
        val projects = cli.listProjects()
        requireAuthContext(cli, expectedAuthContextId)
        return projects
    }

    fun listConfigs(
        projectSlug: String,
        operation: CliOperation = CliOperation.TREE_CONFIGS,
    ): List<DopplerConfig> = cli(operation, CliLogContext(project = projectSlug)).listConfigs(projectSlug)

    fun folderSetup(
        folder: String,
        operation: CliOperation = CliOperation.FOLDER_SETUP,
    ): DopplerFolderSetup = cli(operation).folderSetup(folder)

    /** Bind all initial choices to one account, checking once around the complete read batch. */
    fun loadFolderSetup(folder: String): DopplerFolderSetupSnapshot {
        val cli = cli(CliOperation.FOLDER_SETUP)
        val account = cli.authContextId()
        val setup = cli.folderSetup(folder)
        val projects = cli.listProjects()
        val configs = setup.projectSlug?.let(cli::listConfigs).orEmpty()
        requireFolderSetupAccount(cli, account)
        return DopplerFolderSetupSnapshot(account, setup, projects, configs)
    }

    fun folderSetupConfigs(
        expectedAuthContextId: String,
        projectSlug: String,
    ): List<DopplerConfig> {
        val cli = cli(CliOperation.FOLDER_SETUP, CliLogContext(project = projectSlug))
        requireFolderSetupAccount(cli, expectedAuthContextId)
        val configs = cli.listConfigs(projectSlug)
        requireFolderSetupAccount(cli, expectedAuthContextId)
        return configs
    }

    fun setFolderSetup(
        folder: String,
        projectSlug: String,
        configName: String,
        expectedAuthContextId: String,
    ) {
        val cli = cli(CliOperation.FOLDER_SETUP, CliLogContext(projectSlug, configName))
        requireFolderSetupAccount(cli, expectedAuthContextId)
        cli.setFolderSetup(folder, projectSlug, configName)
    }

    fun clearFolderSetup(
        folder: String,
        expectedAuthContextId: String,
    ) {
        val cli = cli(CliOperation.FOLDER_SETUP)
        requireFolderSetupAccount(cli, expectedAuthContextId)
        cli.clearFolderSetup(folder)
    }

    private fun requireFolderSetupAccount(
        cli: DopplerCli,
        expected: String,
    ) {
        if (cli.authContextId() != expected) {
            throw DopplerCliException(
                "The Doppler account changed. Reload folder setup before applying changes.",
                DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
            )
        }
    }

    fun listConfigs(
        expectedAuthContextId: String,
        projectSlug: String,
    ): List<DopplerConfig> {
        val cli = cli(context = CliLogContext(project = projectSlug))
        requireAuthContext(cli, expectedAuthContextId)
        val configs = cli.listConfigs(projectSlug)
        requireAuthContext(cli, expectedAuthContextId)
        return configs
    }

    fun listSecretNames(
        projectSlug: String,
        configName: String,
    ): Set<String> = cli(context = CliLogContext(projectSlug, configName)).listSecretNames(projectSlug, configName)

    fun listSecretNames(
        expectedAuthContextId: String,
        projectSlug: String,
        configName: String,
        environment: String? = null,
    ): Set<String> {
        val cli = cli(context = CliLogContext(projectSlug, configName, environment))
        requireAuthContext(cli, expectedAuthContextId)
        val names = cli.listSecretNames(projectSlug, configName)
        requireAuthContext(cli, expectedAuthContextId)
        return names
    }

    /** Resolve the CLI login scoped to the launched IDE project, rather than the IDE process directory. */
    internal fun injectionAccount(folder: String): String = cli(CliOperation.RUN_INJECTION, scope = folder).authContextId()

    /** Injection reads computed values without persisting a dotenv file. Account validation is owned by the caller. */
    internal fun downloadEnvironment(
        folder: String,
        projectSlug: String,
        configName: String,
        nameTransformer: DopplerNameTransformer = DopplerNameTransformer.NONE,
    ): Map<String, String> =
        cli(CliOperation.RUN_INJECTION, CliLogContext(projectSlug, configName), scope = folder)
            .downloadEnvironment(projectSlug, configName, nameTransformer)

    fun loadSecrets(
        projectSlug: String,
        configName: String,
        expectedAuthContextId: String? = null,
        operation: CliOperation = CliOperation.CONFIG_OPEN,
        environment: String? = null,
    ): DopplerSecretsSnapshot {
        val cli = cli(operation, CliLogContext(projectSlug, configName, environment))
        val authContextId = cli.authContextId()
        if (expectedAuthContextId != null && expectedAuthContextId != authContextId) {
            throw authContextChanged()
        }
        return loadSecretsAndRevalidate(cli, projectSlug, configName, authContextId)
    }

    /**
     * Loads a config after the tree has already authenticated [expectedAuthContextId]. The
     * snapshot is still withheld until a post-read account check confirms the same identity.
     */
    internal fun loadSecretsAfterTreeValidation(
        projectSlug: String,
        configName: String,
        expectedAuthContextId: String,
        operation: CliOperation = CliOperation.CONFIG_OPEN,
        environment: String? = null,
    ): DopplerSecretsSnapshot {
        val cli = cli(operation, CliLogContext(projectSlug, configName, environment))
        return loadSecretsAndRevalidate(cli, projectSlug, configName, expectedAuthContextId)
    }

    private fun loadSecretsAndRevalidate(
        cli: DopplerCli,
        projectSlug: String,
        configName: String,
        expectedAuthContextId: String,
    ): DopplerSecretsSnapshot {
        val secrets =
            try {
                cli.listSecrets(projectSlug, configName)
            } catch (error: DopplerCliException) {
                // A failed value read can itself be caused by an account switch. Check before
                // propagating it so callers scrub every cached config when authentication changed.
                requireAuthContext(cli, expectedAuthContextId)
                throw error
            }
        requireAuthContext(cli, expectedAuthContextId)
        return DopplerSecretsSnapshot(expectedAuthContextId, secrets)
    }

    /**
     * Applies a diff to a config. Replacements must succeed before old names are deleted.
     */
    fun applyChanges(
        authContextId: String,
        projectSlug: String,
        configName: String,
        expectedSnapshot: Map<String, String?>,
        changes: SecretChanges,
        environment: String? = null,
        onSnapshotAdvanced: (Map<String, String?>) -> Unit = {},
    ) {
        val cli = cli(CliOperation.SAVE, CliLogContext(projectSlug, configName, environment))
        applyChangesWithOptimisticLock(
            expectedSnapshot = expectedSnapshot,
            changes = changes,
            checkAuthContext = { requireAuthContext(cli, authContextId) },
            loadSnapshot = {
                cli.listSecrets(projectSlug, configName).associate { it.name to it.raw }
            },
            deleteSecrets = { names ->
                cli.deleteSecrets(projectSlug, configName, names)
            },
            setSecret = { name, value ->
                cli.setSecret(projectSlug, configName, name, value)
            },
            onSnapshotAdvanced = onSnapshotAdvanced,
        )
    }

    private fun requireAuthContext(
        cli: DopplerCli,
        expected: String,
    ) {
        if (cli.authContextId() != expected) throw authContextChanged()
    }

    private fun authContextChanged() =
        DopplerCliException(
            "The Doppler account changed. Reopen this config under the current account.",
            DopplerCliException.Kind.AUTH_CONTEXT_CHANGED,
        )

    companion object {
        fun getInstance(): DopplerService = service()
    }
}

/**
 * Applies writes only while the remote config still matches the state the editor was based on.
 * The CLI has no atomic compare-and-set operation, so one preflight check protects against stale
 * editors without adding a remote read before every individual mutation.
 * Account identity is checked once before reading. CLI calls cannot pin authentication, so
 * switching accounts during a save remains unsafe.
 */
internal fun applyChangesWithOptimisticLock(
    expectedSnapshot: Map<String, String?>,
    changes: SecretChanges,
    checkAuthContext: () -> Unit,
    loadSnapshot: () -> Map<String, String?>,
    deleteSecrets: (Set<String>) -> Unit,
    setSecret: (String, String) -> Unit,
    onSnapshotAdvanced: (Map<String, String?>) -> Unit = {},
) {
    checkAuthContext()
    if (loadSnapshot() != expectedSnapshot) {
        throw DopplerCliException(
            "This config changed in Doppler after it was opened. Your edits were kept. Copy your edits, reload the config, and reconcile them with the latest server state before saving again.",
            DopplerCliException.Kind.REMOTE_CHANGED,
        )
    }
    var committedSnapshot = expectedSnapshot
    for ((name, value) in changes.upserted) {
        try {
            setSecret(name, value)
        } catch (error: DopplerCliException) {
            if (error.kind != DopplerCliException.Kind.GENERIC || name in expectedSnapshot || changes.removed.isEmpty()) {
                throw error
            }
            throw DopplerCliException(
                "Could not create a replacement secret. No secrets were deleted; earlier writes may have succeeded. " +
                    "Your edits were kept. Check the secret name, permissions, and available capacity in Doppler before retrying. " +
                    "Renaming at a secret limit requires temporary headroom; increase the limit or free unused capacity in Doppler.",
            )
        }
        committedSnapshot = SecretChanges(mapOf(name to value), emptySet()).applyTo(committedSnapshot)
        onSnapshotAdvanced(committedSnapshot)
    }
    if (changes.removed.isNotEmpty()) {
        deleteSecrets(changes.removed)
        committedSnapshot = committedSnapshot - changes.removed
        onSnapshotAdvanced(committedSnapshot)
    }
}

/** The minimal set of writes needed to turn the remote config into the edited document. */
data class SecretChanges(
    val upserted: Map<String, String>,
    val removed: Set<String>,
) {
    val isEmpty: Boolean get() = upserted.isEmpty() && removed.isEmpty()
    val totalOperations: Int get() = upserted.size + removed.size

    fun applyTo(original: Map<String, String?>): Map<String, String?> {
        val result = (original - removed).toMutableMap()
        for ((name, value) in upserted) {
            // A restricted secret remains unreadable after it is overwritten.
            result[name] = if (name in original && original[name] == null) null else value
        }
        return result
    }

    companion object {
        fun unvaluedAdditions(
            original: Map<String, String?>,
            edited: ParsedSecrets,
        ): Set<String> =
            edited.values
                .filter { (name, value) -> name !in original && value == null }
                .keys + edited.cleared.filterNot(original::containsKey)

        fun between(
            original: Map<String, String?>,
            edited: ParsedSecrets,
        ): SecretChanges {
            val upserted =
                edited.values
                    .mapNotNull { (name, value) -> value?.let { name to it } }
                    .filter { (name, value) -> original[name] != value }
                    .toMap()

            // Emptying a value deletes the secret, including a restricted one. A value left as
            // the literal `null` is the unreadable placeholder and must stay a no-op.
            val removed =
                (original.keys - edited.values.keys - edited.cleared) +
                    edited.cleared.filter { original.containsKey(it) }

            return SecretChanges(upserted, removed)
        }
    }
}
