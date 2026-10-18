package com.vk.doppler.cli

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

private val callIds = AtomicLong()

enum class CliOperation {
    TREE_RELOAD, TREE_CONFIGS, CONFIG_OPEN, AUTO_REFRESH, ACCOUNT_VALIDATION, SAVE, SAVE_RECOVERY, AUTOCOMPLETE, TEST_CONNECTION, OTHER,
    TREE_STARTUP, TREE_PANEL_FOCUS, TREE_WINDOW_FOCUS, TREE_REFRESH_BUTTON, TREE_RETRY, TREE_EXPAND, CONFIG_RELOAD, FOLDER_SETUP, FOLDER_SETUP_POLL,
    RUN_INJECTION,
}

/** Reveals at most 60% of a name, measured in Unicode code points, as a quoted log field. */
internal fun maskedCliName(value: String): String {
    val count = value.codePointCount(0, value.length)
    val visible = (count.toLong() * 3 / 5).toInt()
    val prefix = value.substring(0, value.offsetByCodePoints(0, visible))
    val escaped = buildString {
        for (char in prefix) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt() ||
                    char == '\u2028' || char == '\u2029' ->
                    append("\\u" + char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
    }
    return "\"$escaped....\""
}

/** Metadata only; never supply tokens, secret names or values here. */
data class CliLogContext(val project: String? = null, val config: String? = null, val environment: String? = null) {
    internal fun maskedFields(): String = listOfNotNull(
        project?.let { "project=${maskedCliName(it)}" },
        config?.let { "config=${maskedCliName(it)}" },
        environment?.let { "env=${maskedCliName(it)}" },
    ).joinToString(" ")
}

/** Only project/config operands may be partially shown. All other operands stay redacted. */
internal fun sanitizedCliCommand(args: List<String>): String {
    val commands = setOf("me", "projects", "configs", "secrets", "configure", "--version")
    val switches = setOf("--json", "--no-check-version", "--raw", "--only-names", "--yes", "--silent", "--no-file", "--no-fallback", "--name-transformer")
    val options = setOf("--project", "--config")
    var operand = false
    var terminated = false
    return "doppler " + args.mapIndexed { index, arg ->
        when {
            terminated -> "<redacted>"
            operand -> { operand = false; maskedCliName(arg) }
            index == 0 -> if (arg in commands) arg else "<redacted>"
            index == 1 && args.firstOrNull() == "secrets" && arg in setOf("set", "delete", "download") -> arg
            index == 1 && args.firstOrNull() == "configure" && arg in setOf("get", "set", "unset") -> arg
            index > 0 && args[index - 1] == "--name-transformer" &&
                DopplerNameTransformer.entries.any { it.cliName == arg } -> arg
            arg == "--" -> { terminated = true; "--" }
            arg in options -> { operand = true; arg }
            arg in switches -> arg
            else -> "<redacted>"
        }
    }.joinToString(" ")
}

/** Logs no process output, input, exception details, environment, or executable path. */
internal fun <T> traceCliCall(
    args: List<String>,
    enabled: Boolean,
    log: (String) -> Unit,
    operation: CliOperation = CliOperation.OTHER,
    context: CliLogContext = CliLogContext(),
    relatedConfigs: List<CliLogContext> = emptyList(),
    execute: ((Int, Boolean) -> Unit) -> T,
): T {
    if (!enabled) return execute { _, _ -> }
    val id = callIds.incrementAndGet()
    val start = System.nanoTime()
    var exit: Int? = null
    var timeout = false
    var success = false
    val fields = context.maskedFields().let { if (it.isEmpty()) "" else " $it" } +
        if (relatedConfigs.isEmpty()) "" else relatedConfigs.joinToString(
            prefix = " openConfigs=[", postfix = "]", separator = ", ",
        ) { "{${it.maskedFields()}}" }
    val prefix = "Doppler CLI #$id operation=$operation$fields"
    log("$prefix START ${sanitizedCliCommand(args)}")
    try {
        val result = execute { code, timedOut -> exit = code; timeout = timedOut }
        success = true
        return result
    } finally {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        log("$prefix END durationMs=$elapsed exit=${exit ?: "unavailable"} timeout=$timeout result=${if (success) "success" else "failure"}")
    }
}
