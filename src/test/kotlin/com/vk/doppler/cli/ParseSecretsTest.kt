package com.vk.doppler.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the payload shape actually produced by `doppler secrets --json --raw` (CLI v3.77).
 *
 * Without `--raw` the CLI omits the `raw` field entirely, which previously made every secret
 * read back as null.
 */
class ParseSecretsTest {
    private val realPayload =
        """
        {
          "DB_URL": {
            "computed": "psql://app@localhost/appdb",
            "computedValueType": { "type": "url" },
            "computedVisibility": "masked",
            "note": "",
            "raw": "psql://app@localhost/appdb",
            "rawValueType": { "type": "url" },
            "rawVisibility": "masked"
          },
          "LOGGING": {
            "computed": "dev",
            "computedValueType": { "type": "string" },
            "computedVisibility": "unmasked",
            "note": "",
            "raw": "${'$'}{DOPPLER_ENVIRONMENT}",
            "rawValueType": { "type": "string" },
            "rawVisibility": "unmasked"
          },
          "LOCKED_DOWN": {
            "computed": null,
            "computedVisibility": "restricted",
            "note": "",
            "raw": null,
            "rawVisibility": "restricted"
          },
          "DOPPLER_PROJECT": {
            "computed": "example-project",
            "computedVisibility": "unmasked",
            "note": "",
            "raw": "example-project",
            "rawVisibility": "unmasked"
          }
        }
        """.trimIndent()

    private fun parse() = parseSecrets(JsonParser.parseString(realPayload))

    @Test
    fun `reads raw values rather than interpolated ones`() {
        val logging = parse().single { it.name == "LOGGING" }
        assertEquals("\${DOPPLER_ENVIRONMENT}", logging.raw)
        assertEquals("dev", logging.computed)
    }

    @Test
    fun `plain secrets are readable`() {
        val dbUrl = parse().single { it.name == "DB_URL" }
        assertEquals("psql://app@localhost/appdb", dbUrl.raw)
        assertFalse(dbUrl.restricted)
    }

    @Test
    fun `restricted secrets have no raw value`() {
        val restricted = parse().single { it.name == "LOCKED_DOWN" }
        assertNull(restricted.raw)
        assertTrue(restricted.restricted)
    }

    @Test
    fun `doppler managed secrets are excluded`() {
        assertTrue(parse().none { it.name.startsWith("DOPPLER_") })
    }

    @Test
    fun `secrets are returned sorted`() {
        assertEquals(listOf("DB_URL", "LOCKED_DOWN", "LOGGING"), parse().map { it.name })
    }

    @Test
    fun `tolerates a secrets wrapper object`() {
        val wrapped = JsonParser.parseString("""{"secrets": {"A": {"raw": "1", "computed": "1"}}}""")
        assertEquals(listOf("A"), parseSecrets(wrapped).map { it.name })
    }

    @Test
    fun `tolerates a secrets wrapper containing a secret named raw`() {
        val wrapped =
            JsonParser.parseString(
                """{"secrets": {"raw": {"raw": "1", "computed": "1"}}}""",
            )
        val secret = parseSecrets(wrapped).single()
        assertEquals("raw", secret.name)
        assertEquals("1", secret.raw)
    }

    @Test
    fun `parses a primitive secret named secrets from the bare map`() {
        val secrets = parseSecrets(JsonParser.parseString("""{"secrets": "value", "A": "1"}"""))
        assertEquals(listOf("A", "secrets"), secrets.map { it.name })
        assertEquals("value", secrets.single { it.name == "secrets" }.raw)
    }

    @Test
    fun `parses an object secret named secrets from the bare map`() {
        val secrets =
            parseSecrets(
                JsonParser.parseString("""{"secrets": {"raw": "value", "computed": "value"}}"""),
            )
        assertEquals(listOf("secrets"), secrets.map { it.name })
        assertEquals("value", secrets.single().raw)
    }

    @Test
    fun `empty configs are valid`() {
        assertTrue(parseSecrets(JsonParser.parseString("{}")).isEmpty())
        assertTrue(parseSecrets(JsonParser.parseString("""{"secrets": {}}""")).isEmpty())
    }

    @Test
    fun `rejects malformed snapshots instead of treating them as empty configs`() {
        listOf(
            "[]",
            "null",
            """{"secrets": []}""",
            """{"A": []}""",
            """{"A": null}""",
            """{"A": {"computed": "interpolated"}}""",
            """{"A": {"raw": {"unexpected": "value"}}}""",
        ).forEach { payload ->
            try {
                parseSecrets(JsonParser.parseString(payload))
                fail("Expected DopplerCliException for malformed snapshot")
            } catch (expected: DopplerCliException) {
                assertTrue(expected.message!!.isNotBlank())
                assertFalse("Errors must not include the payload", expected.message!!.contains(payload))
            }
        }
    }

    @Test
    fun `restricted visibility permits omitted raw values`() {
        val secrets =
            parseSecrets(
                JsonParser.parseString("""{"A": {"rawVisibility": "restricted", "computed": null}}"""),
            )
        assertTrue(secrets.single().restricted)
    }
}
