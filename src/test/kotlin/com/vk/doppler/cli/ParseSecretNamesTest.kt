package com.vk.doppler.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pins the payload of `doppler secrets --json --only-names` (CLI v3.77): `{"NAME": {}}`. */
class ParseSecretNamesTest {

    private fun parse(payload: String) = parseSecretNames(JsonParser.parseString(payload))

    @Test
    fun `reads the names from the bare map`() {
        assertEquals(setOf("DB_URL", "API_KEY"), parse("""{"DB_URL": {}, "API_KEY": {}}"""))
    }

    @Test
    fun `keeps the order reported by the cli`() {
        assertEquals(listOf("B", "A"), parse("""{"B": {}, "A": {}}""").toList())
    }

    @Test
    fun `doppler managed secrets are excluded`() {
        assertEquals(setOf("A"), parse("""{"A": {}, "DOPPLER_PROJECT": {}, "DOPPLER_CONFIG": {}}"""))
    }

    @Test
    fun `a secret literally named secrets is kept`() {
        assertEquals(setOf("secrets"), parse("""{"secrets": {}}"""))
    }

    @Test
    fun `empty configs are valid`() {
        assertTrue(parse("{}").isEmpty())
    }

    @Test
    fun `values are never required`() {
        assertEquals(setOf("A", "B"), parse("""{"A": null, "B": "ignored"}"""))
    }

    @Test
    fun `rejects payloads that are not an object`() {
        listOf("[]", "null", "\"text\"").forEach { payload ->
            try {
                parse(payload)
                fail("Expected DopplerCliException for $payload")
            } catch (expected: DopplerCliException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }
}
