package com.vk.doppler.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.thisLogger
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import com.vk.doppler.settings.DopplerSettings

/**
 * Thin wrapper around the `doppler` binary.
 *
 * Every call goes through [runJson] so that error payloads, timeouts and missing-binary
 * situations are normalised into [DopplerCliException].
 */
class DopplerCli(
    private val executablePath: String,
    private val operation: CliOperation = CliOperation.OTHER,
    private val logContext: CliLogContext = CliLogContext(),
    private val relatedConfigs: List<CliLogContext> = emptyList(),
) {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000

        /**
         * Doppler returns this (exit 1) when no token is configured for the scope.
         * Matched case-insensitively against the `error` payload.
         */
        private val AUTH_ERROR_MARKERS = listOf(
            "you must provide a token",
            "invalid auth token",
            "doppler login",
            "unauthorized",
            "forbidden",
            "not authorized",
            "access denied",
            "permission denied",
        )
    }

    fun version(): String {
        val raw = runRaw(listOf("--version"), stdin = null, json = false)
        return raw.trim()
    }

    /** Verifies that a usable token is configured. Throws [DopplerCliException] otherwise. */
    fun checkAuth() {
        authContextId()
    }

    /** Stable, non-reversible identifier for the CLI account and token context. */
    fun authContextId(): String = accountInfo().authContextId

    /** Reads identity and its fingerprint in one call, without retaining authentication fields. */
    fun accountInfo(): DopplerAccountInfo {
        val me = runJson(listOf("me")) as? JsonObject
            ?: throw DopplerCliException("The Doppler CLI returned invalid account information.")
        val workplace = me.get("workplace") as? JsonObject
            ?: throw DopplerCliException("The Doppler CLI returned incomplete account information.")
        val identity = listOf(
            me.string("slug"),
            me.string("type"),
            me.string("token_preview"),
            workplace.string("slug"),
        )
        if (identity.any { it.isNullOrBlank() }) {
            throw DopplerCliException("The Doppler CLI returned incomplete account information.")
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(identity.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
        return DopplerAccountInfo(
            authContextId = bytes.joinToString("") { "%02x".format(it) },
            slug = requireNotNull(identity[0]),
            type = requireNotNull(identity[1]),
            workplaceSlug = requireNotNull(identity[3]),
            workplaceName = workplace.string("name")?.takeIf { it.isNotBlank() },
            name = me.string("name")?.takeIf { it.isNotBlank() },
        )
    }

    fun listProjects(): List<DopplerProject> {
        val element = runJson(listOf("projects"))
        return unwrapArray(element, "projects").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val slug = obj.string("slug") ?: obj.string("name") ?: return@mapNotNull null
            DopplerProject(
                slug = slug,
                name = obj.string("name") ?: slug,
                description = obj.string("description"),
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    fun listConfigs(projectSlug: String): List<DopplerConfig> {
        val element = runJson(listOf("configs", "--project", projectSlug))
        return unwrapArray(element, "configs").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            DopplerConfig(
                name = name,
                environment = obj.string("environment") ?: obj.string("stage") ?: name,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Reads only the two non-sensitive settings, including inherited folder defaults. */
    fun folderSetup(folder: String): DopplerFolderSetup = parseFolderSetup(
        runJson(listOf("configure", "get", "project", "config", "--scope", folder)),
    )

    fun setFolderSetup(folder: String, projectSlug: String, configName: String) {
        for (value in listOf(projectSlug, configName)) {
            require(value.isNotBlank() && value.none { it == '=' || it.isISOControl() }) {
                "Invalid Doppler project or config name"
            }
        }
        runRaw(
            listOf("configure", "set", "--scope", folder, "--silent", "--", "project=$projectSlug", "config=$configName"),
            stdin = null,
            json = true,
        )
    }

    fun clearFolderSetup(folder: String) {
        runRaw(
            listOf("configure", "unset", "--scope", folder, "--silent", "--", "project", "config"),
            stdin = null,
            json = true,
        )
    }

    /** Reads all non-reserved secrets for a config. */
    fun listSecrets(projectSlug: String, configName: String): List<DopplerSecret> {
        // Without --raw the CLI omits the `raw` field entirely and only returns interpolated
        // `computed` values, which would discard Doppler's ${REFERENCE} syntax on save.
        val element = runJson(
            listOf("secrets", "--project", projectSlug, "--config", configName, "--raw")
        )
        return parseSecrets(element)
    }

    /** Reads only the secret names of a config; the CLI never returns the values. */
    fun listSecretNames(projectSlug: String, configName: String): Set<String> {
        val element = runJson(
            listOf("secrets", "--only-names", "--project", projectSlug, "--config", configName)
        )
        return parseSecretNames(element)
    }

    /**
     * Sets a single secret. The value is streamed over stdin so it never appears in the
     * process arguments, where it would be readable by other local users.
     */
    fun setSecret(projectSlug: String, configName: String, name: String, value: String) {
        require(!DopplerReservedSecrets.isReserved(name)) { "Cannot write reserved secret $name" }
        requireValidName(name)
        runRaw(
            args = listOf("secrets", "set", "--project", projectSlug, "--config", configName, "--", name),
            stdin = value,
            json = true,
        )
    }

    fun deleteSecrets(projectSlug: String, configName: String, names: Collection<String>) {
        if (names.isEmpty()) return
        val safeNames = names.filterNot { DopplerReservedSecrets.isReserved(it) }
        if (safeNames.isEmpty()) return
        safeNames.forEach(::requireValidName)
        runRaw(
            listOf("secrets", "delete", "--yes", "--project", projectSlug, "--config", configName, "--") +
                safeNames,
            stdin = null,
            json = true,
        )
    }

    private fun requireValidName(name: String) {
        if (!DopplerSecretNames.isValid(name)) {
            throw DopplerCliException(
                "Invalid secret name. Use only letters, digits, _, /, :, ., and -.",
            )
        }
    }

    private fun runJson(args: List<String>, stdin: String? = null): JsonElement {
        val raw = runRaw(args, stdin, json = true)
        if (raw.isBlank()) throw DopplerCliException("The Doppler CLI returned no JSON output.")
        return try {
            JsonParser.parseString(raw)
        } catch (e: JsonSyntaxException) {
            throw DopplerCliException("Could not parse Doppler CLI output as JSON.", cause = e)
        }
    }

    private fun runRaw(args: List<String>, stdin: String?, json: Boolean): String {
        val parameters = args.toMutableList()
        if (json) {
            val jsonFlags = listOf("--json", "--no-check-version")
            val terminatorIndex = parameters.indexOf("--")
            if (terminatorIndex < 0) {
                parameters.addAll(jsonFlags)
            } else {
                parameters.addAll(terminatorIndex, jsonFlags)
            }
        }
        return traceCliCall(parameters, DopplerSettings.getInstance().logCliCalls, { thisLogger().info(it) }, operation, logContext, relatedConfigs) { reportExit ->
            executeRaw(parameters, stdin, reportExit)
        }
    }

    private fun executeRaw(parameters: List<String>, stdin: String?, reportExit: (Int, Boolean) -> Unit): String {
        val commandLine = GeneralCommandLine(executablePath)
            .withParameters(parameters)
            .withCharset(StandardCharsets.UTF_8)

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: Exception) {
            throw DopplerCliException(
                "Could not start the Doppler CLI at $executablePath.",
                DopplerCliException.Kind.CLI_NOT_FOUND,
                e,
            )
        }

        val stdinFailure = AtomicReference<Exception?>(null)
        if (stdin != null) {
            val payload = stdin.toByteArray(StandardCharsets.UTF_8)
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    try {
                        handler.processInput.use { stream ->
                            stream.write(payload)
                            stream.flush()
                        }
                    } catch (e: Exception) {
                        stdinFailure.set(e)
                        // Never leave the CLI blocked on (or acting on) a truncated value.
                        handler.destroyProcess()
                    }
                }
            })
        }

        val output = handler.runProcess(DEFAULT_TIMEOUT_MS, true)
        reportExit(output.exitCode, output.isTimeout)
        stdinFailure.get()?.let { e ->
            thisLogger().warn("Failed to write the secret value to the Doppler CLI stdin", e)
            throw DopplerCliException("Could not pass the secret value to the Doppler CLI.", cause = e)
        }
        if (output.isTimeout) {
            throw DopplerCliException("The Doppler CLI did not respond within ${DEFAULT_TIMEOUT_MS / 1000}s.")
        }

        val stdout = output.stdout
        val stderr = output.stderr

        // The CLI reports failures as {"error": "..."} and exits non-zero.
        val errorMessage = extractError(stdout) ?: extractError(stderr)
        if (output.exitCode != 0 || errorMessage != null) {
            val message = errorMessage
                ?: stderr.takeIf { it.isNotBlank() }
                ?: "The Doppler CLI exited with code ${output.exitCode}."
            throw DopplerCliException(message.trim(), classify(message))
        }
        return stdout
    }

    private fun classify(message: String): DopplerCliException.Kind {
        val normalized = message.lowercase()
        return if (AUTH_ERROR_MARKERS.any { normalized.contains(it) }) {
            DopplerCliException.Kind.NOT_AUTHENTICATED
        } else {
            DopplerCliException.Kind.GENERIC
        }
    }

    private fun extractError(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            (JsonParser.parseString(trimmed) as? JsonObject)?.string("error")
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun unwrapArray(element: JsonElement, wrapperKey: String): List<JsonElement> = when {
        element.isJsonArray -> element.asJsonArray.toList()
        element.isJsonObject -> element.asJsonObject.getAsJsonArray(wrapperKey)?.toList().orEmpty()
        else -> emptyList()
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive) value.asString else null
    }
}

data class DopplerFolderSetup(val projectSlug: String? = null, val configName: String? = null) {
    fun matchesProject(slug: String): Boolean = projectSlug == slug
    fun matchesConfig(slug: String, name: String): Boolean = projectSlug == slug && configName == name
}

internal fun parseFolderSetup(element: JsonElement): DopplerFolderSetup {
    val root = element as? JsonObject ?: throw DopplerCliException("Expected Doppler folder configuration.")
    fun option(key: String): String? {
        val value = root.get("enclave.$key") ?: root.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw DopplerCliException("Invalid Doppler folder configuration.")
        }
        return value.asString.takeIf { it.isNotBlank() }
    }
    return DopplerFolderSetup(option("project"), option("config"))
}

/**
 * Maps the output of `doppler secrets --json --raw` into [DopplerSecret]s.
 *
 * Exposed separately from process execution so the exact CLI payload shape can be tested.
 */
internal fun parseSecrets(element: JsonElement): List<DopplerSecret> {
    val root = element as? JsonObject
        ?: throw DopplerCliException("Expected a Doppler secrets object.")
    // Prefer the documented bare map when `secrets` is itself a secret value.
    val wrappedMap = root.get("secrets") as? JsonObject
    val map = if (
        root.size() == 1 &&
        wrappedMap != null &&
        !root.get("secrets").isSecretValue() &&
        wrappedMap.entrySet().all { (_, value) -> value.isSecretValue() }
    ) {
        wrappedMap
    } else {
        root
    }

    return map.entrySet().mapNotNull { (name, value) ->
        if (DopplerReservedSecrets.isReserved(name)) return@mapNotNull null
        when {
            value is JsonObject -> {
                if (!value.has("raw") && value.stringOrNull("rawVisibility") != "restricted") {
                    throw DopplerCliException("Doppler secrets output is missing a raw value. Ensure the CLI supports --raw.")
                }
                DopplerSecret(
                    name = name,
                    // `raw` is null only when the secret is restricted and cannot be read back.
                    raw = value.stringOrNull("raw"),
                    computed = value.stringOrNull("computed"),
                    note = value.stringOrNull("note"),
                )
            }
            value != null && value.isJsonPrimitive -> {
                val text = value.asString
                DopplerSecret(name = name, raw = text, computed = text, note = null)
            }
            else -> throw DopplerCliException("Doppler secrets output contains an invalid value.")
        }
    }.sortedBy { it.name }
}

/**
 * Maps the output of `doppler secrets --json --only-names`, a bare object of `name -> {}`, to
 * the set of names. Values are never read, so nothing secret is held in memory.
 */
internal fun parseSecretNames(element: JsonElement): Set<String> {
    val root = element as? JsonObject
        ?: throw DopplerCliException("Expected a Doppler secrets object.")
    return root.keySet().filterNotTo(linkedSetOf()) { DopplerReservedSecrets.isReserved(it) }
}

private fun JsonElement.isSecretValue(): Boolean = when {
    isJsonPrimitive -> true
    isJsonObject -> {
        val rawValue = asJsonObject.get("raw")
        val hasValidRawValue = rawValue != null && (rawValue.isJsonNull || rawValue.isJsonPrimitive)
        val isRestrictedWithoutRaw = rawValue == null &&
            asJsonObject.get("rawVisibility")?.let { it.isJsonPrimitive && it.asString == "restricted" } == true
        hasValidRawValue || isRestrictedWithoutRaw
    }
    else -> false
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return when {
        value.isJsonNull -> null
        value.isJsonPrimitive -> value.asString
        else -> throw DopplerCliException("Doppler secrets output contains an invalid '$key' field.")
    }
}
