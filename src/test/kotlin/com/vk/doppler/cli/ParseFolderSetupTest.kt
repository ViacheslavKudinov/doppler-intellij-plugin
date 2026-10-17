package com.vk.doppler.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParseFolderSetupTest {
    private fun parse(payload: String) = parseFolderSetup(JsonParser.parseString(payload))

    @Test
    fun `reads CLI prefixed keys and unprefixed keys`() {
        val expected = DopplerFolderSetup("backend", "dev")
        assertEquals(expected, parse("""{"enclave.project":"backend","enclave.config":"dev"}"""))
        assertEquals(expected, parse("""{"project":"backend","config":"dev"}"""))
    }

    @Test
    fun `prefixed values take precedence including empty overrides`() {
        assertEquals(DopplerFolderSetup("backend", "dev"), parse(
            """{"enclave.project":"backend","enclave.config":"dev","project":"other","config":"prod"}""",
        ))
        assertEquals(DopplerFolderSetup(), parse(
            """{"enclave.project":"","enclave.config":" ","project":"other","config":"prod"}""",
        ))
    }

    @Test
    fun `accepts missing and partial setup without inventing the other value`() {
        assertEquals(DopplerFolderSetup(), parse("{}"))
        for (prefix in listOf("", "enclave.")) {
            assertEquals(DopplerFolderSetup(projectSlug = "backend"), parse("""{"${prefix}project":"backend"}"""))
            assertEquals(DopplerFolderSetup(configName = "dev"), parse("""{"${prefix}config":"dev"}"""))
        }
        assertEquals(DopplerFolderSetup("backend", "dev"), parse("""{"enclave.project":"backend","config":"dev"}"""))
    }

    @Test
    fun `empty and whitespace values mean unconfigured`() {
        assertEquals(DopplerFolderSetup(), parse("""{"enclave.project":"","enclave.config":"  "}"""))
        assertEquals(DopplerFolderSetup(configName = "dev"), parse("""{"project":" ","config":"dev"}"""))
    }

    @Test
    fun `ignores unrelated response fields`() {
        assertEquals(DopplerFolderSetup("backend", "dev"), parse(
            """{"project":"backend","config":"dev","unrelated":{"nested":true}}""",
        ))
    }

    @Test
    fun `rejects non-object responses`() {
        for (payload in listOf("[]", "null", "true", "42", "\"text\"")) assertMalformed(payload)
    }

    @Test
    fun `rejects null numeric boolean array and object option values`() {
        for (key in listOf("project", "config", "enclave.project", "enclave.config")) {
            for (value in listOf("null", "42", "true", "[]", "{}")) {
                assertMalformed("""{"$key":$value}""")
            }
        }
        // A valid fallback must not hide a malformed preferred field.
        assertMalformed("""{"enclave.project":null,"project":"backend"}""")
    }

    private fun assertMalformed(payload: String) {
        try {
            parse(payload)
            fail("Expected DopplerCliException for $payload")
        } catch (expected: DopplerCliException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
