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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around the `doppler` binary.
 *
 * Every call goes through [runJson] so that error payloads, timeouts and missing-binary
 * situations are normalised into [DopplerCliException].
 */
class DopplerCli(private val executablePath: String) {

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
        )
    }

    fun version(): String {
        val raw = runRaw(listOf("--version"), stdin = null, json = false)
        return raw.trim()
    }

    /** Verifies that a usable token is configured. Throws [DopplerCliException] otherwise. */
    fun checkAuth() {
        runJson(listOf("me"))
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

    /** Reads all non-reserved secrets for a config. */
    fun listSecrets(projectSlug: String, configName: String): List<DopplerSecret> {
        // Without --raw the CLI omits the `raw` field entirely and only returns interpolated
        // `computed` values, which would discard Doppler's ${REFERENCE} syntax on save.
        val element = runJson(
            listOf("secrets", "--project", projectSlug, "--config", configName, "--raw")
        )
        return parseSecrets(element)
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
