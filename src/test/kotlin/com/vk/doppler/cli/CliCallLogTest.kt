package com.vk.doppler.cli

import org.junit.Assert.*
import org.junit.Test

class CliCallLogTest {
    @Test
    fun `folder setup logs reads writes and clears without exposing scope or values`() {
        for (action in listOf("get", "set", "unset")) {
            val logs = mutableListOf<String>()
            traceCliCall(listOf("configure", action, "--scope", "/private/example-folder", "--json", "--", "project=private-project", "config=private-config"),
                true, logs::add, CliOperation.FOLDER_SETUP) { report -> report(0, false) }
            assertTrue(logs.first().contains("START doppler configure $action"))
            assertTrue(logs.last().contains("END durationMs="))
            assertTrue(logs.last().contains("exit=0 timeout=false result=success"))
            for (value in listOf("/private/example-folder", "private-project", "private-config")) {
                assertFalse(logs.joinToString().contains(value))
            }
        }
    }

    @Test
    fun `refresh account check includes all open config contexts with masked names`() {
        val logs = mutableListOf<String>()
        traceCliCall(listOf("me"), true, logs::add, CliOperation.AUTO_REFRESH,
            relatedConfigs = listOf(
                CliLogContext("backend", "production", "production"),
                CliLogContext("frontend", "development"),
            )) { report -> report(0, false) }
        assertEquals(2, logs.size)
        for (line in logs) {
            assertTrue(line.contains("openConfigs=[{project=\"back....\" config=\"produc....\" env=\"produc....\"}, " +
                "{project=\"fron....\" config=\"develo....\"}]"))
            for (name in listOf("backend", "frontend", "production", "development")) {
                assertFalse(line.contains(name))
            }
        }
        assertTrue(logs.first().contains("START doppler me"))
    }

    @Test
    fun `event labels are included in both start and finish entries`() {
        for (operation in listOf(CliOperation.TREE_STARTUP, CliOperation.TREE_PANEL_FOCUS,
            CliOperation.TREE_WINDOW_FOCUS, CliOperation.TREE_REFRESH_BUTTON, CliOperation.TREE_RETRY,
            CliOperation.TREE_EXPAND, CliOperation.AUTO_REFRESH, CliOperation.CONFIG_RELOAD,
            CliOperation.FOLDER_SETUP, CliOperation.FOLDER_SETUP_POLL)) {
            val logs = mutableListOf<String>()
            traceCliCall(listOf("me"), true, logs::add, operation) { report -> report(0, false) }
            assertEquals(2, logs.size)
            assertTrue(logs.all { it.contains("operation=$operation ") })
        }
    }

    @Test
    fun `logs masked metadata and timing without secrets or output`() {
        val logs = mutableListOf<String>()
        val result = traceCliCall(
            listOf("secrets", "set", "--project", "private-project", "--config", "private-config",
                "--json", "--", "SECRET_NAME=synthetic-value"),
            true, logs::add, CliOperation.SAVE,
            CliLogContext("private-project", "private-config", "production"),
        ) { report -> report(0, false); "synthetic-output" }
        assertEquals("synthetic-output", result)
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("START doppler secrets set --project \"private-p....\" --config \"private-....\""))
        assertTrue(logs[1].contains("END durationMs="))
        for (line in logs) {
            assertTrue(line.contains("operation=SAVE project=\"private-p....\" config=\"private-....\" env=\"produc....\""))
            assertFalse(line.contains("production"))
        }
        assertTrue(logs[1].contains("exit=0 timeout=false result=success"))
        for (sensitive in listOf("private-project", "private-config", "SECRET_NAME", "synthetic-value", "synthetic-output")) {
            assertFalse(logs.joinToString().contains(sensitive))
        }
        assertEquals(logs[0].substringBefore(" START"), logs[1].substringBefore(" END"))
    }

    @Test
    fun `disabled tracing executes without emitting logs`() {
        val result = traceCliCall(listOf("me"), false, { fail("Logging must be disabled") }) { report ->
            report(0, false)
            42
        }
        assertEquals(42, result)
    }

    @Test
    fun `failure and timeout log metadata without exception contents`() {
        val logs = mutableListOf<String>()
        val failure = IllegalStateException("synthetic-sensitive-error")
        try {
            traceCliCall(listOf("me"), true, logs::add, CliOperation.AUTO_REFRESH) { report ->
                report(-1, true)
                throw failure
            }
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertTrue(logs.last().contains("exit=-1 timeout=true result=failure"))
        assertFalse(logs.joinToString().contains(failure.message!!))
    }

    @Test
    fun `unknown flags and newline injection are redacted`() {
        assertEquals("doppler secrets --project \"--r....\" <redacted> <redacted> -- <redacted>",
            sanitizedCliCommand(listOf("secrets", "--project", "--raw", "--token=synthetic-token",
                "injected\nlog", "--", "--json")))
    }

    @Test
    fun `masking rounds down and hides short names without splitting Unicode`() {
        assertEquals("\"....\"", maskedCliName(""))
        assertEquals("\"....\"", maskedCliName("a"))
        assertEquals("\"a....\"", maskedCliName("ab"))
        assertEquals("\"d....\"", maskedCliName("dev"))
        assertEquals("\"produc....\"", maskedCliName("production"))
        assertEquals("\"😀....\"", maskedCliName("😀😀"))
    }

    @Test
    fun `masked metadata escapes controls quotes and backslashes on account checks`() {
        val logs = mutableListOf<String>()
        traceCliCall(listOf("me"), true, logs::add, CliOperation.AUTO_REFRESH,
            CliLogContext("a\n\r\t\"\\\u2028\u202eabcdefghijklmno")) { report -> report(0, false) }
        assertEquals(2, logs.size)
        for (line in logs) {
            assertFalse(line.any { it.isISOControl() || it == '\u2028' || it == '\u202e' })
            assertTrue(line.contains("\\u000a"))
            assertTrue(line.contains("\\\""))
            assertTrue(line.contains("\\\\"))
            assertFalse(line.contains("config="))
            assertFalse(line.contains("env="))
        }
    }
}
