package com.vk.doppler.injection

import com.vk.doppler.cli.DopplerReservedSecrets
import com.vk.doppler.editor.SecretsDocumentFormat
import com.vk.doppler.editor.SecretsFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretNameMappingTest {
    private fun pair(
        name: String,
        newName: String,
    ) = SecretNameMapping(name, newName)

    @Test fun `matching secrets are renamed with their values and others keep their names`() {
        val secrets = mapOf("DB_URL" to "u", "API_KEY" to "k", "OTHER" to "o")
        val result = applySecretNameMapping(secrets, listOf(pair("DB_URL", "DATABASE_URL")))
        assertEquals(mapOf("API_KEY" to "k", "OTHER" to "o", "DATABASE_URL" to "u"), result)
        assertTrue("DB_URL" !in result)
    }

    @Test fun `mappings whose secret is missing are ignored`() {
        val secrets = mapOf("A" to "1")
        assertEquals(secrets, applySecretNameMapping(secrets, listOf(pair("MISSING", "NEW"))))
    }

    @Test fun `no usable mappings returns the same map`() {
        val secrets = mapOf("A" to "1")
        assertSame(secrets, applySecretNameMapping(secrets, emptyList()))
        assertSame(secrets, applySecretNameMapping(secrets, listOf(pair("", "X"), pair("A", ""), pair("A", "BAD=NAME"))))
    }

    @Test fun `names are matched case sensitively`() {
        val secrets = mapOf("Key" to "1")
        assertEquals(secrets, applySecretNameMapping(secrets, listOf(pair("KEY", "NEW"))))
    }

    @Test fun `renames use original names and do not chain`() {
        val secrets = mapOf("A" to "a", "B" to "b")
        val result = applySecretNameMapping(secrets, listOf(pair("A", "B"), pair("B", "C")))
        assertEquals(mapOf("B" to "a", "C" to "b"), result)
    }

    @Test fun `mapped value wins over an unmapped secret with the same new name`() {
        val secrets = mapOf("NEW" to "old", "A" to "mapped")
        assertEquals(mapOf("NEW" to "mapped"), applySecretNameMapping(secrets, listOf(pair("A", "NEW"))))
    }

    @Test fun `first mapping wins for a duplicate current name`() {
        val secrets = mapOf("A" to "1")
        assertEquals(mapOf("X" to "1"), applySecretNameMapping(secrets, listOf(pair("A", "X"), pair("A", "Y"))))
    }

    @Test fun `values including empty and multiline are unchanged and input is not mutated`() {
        val secrets = linkedMapOf("A" to "", "B" to "one\ntwo")
        val copy = LinkedHashMap(secrets)
        val result = applySecretNameMapping(secrets, listOf(pair("A", "X"), pair("B", "Y")))
        assertEquals(mapOf("X" to "", "Y" to "one\ntwo"), result)
        assertEquals(copy, secrets)
    }

    @Test fun `valid mappings pass validation and blank rows are ignored`() {
        assertNull(validateSecretNameMappings(emptyList()))
        assertNull(validateSecretNameMappings(listOf(pair("A", "B"), pair("", ""), pair("  ", " "))))
    }

    @Test fun `incomplete rows are rejected`() {
        assertNotNull(validateSecretNameMappings(listOf(pair("", "NEW"))))
        assertNotNull(validateSecretNameMappings(listOf(pair("OLD", ""))))
    }

    @Test fun `invalid new names are rejected`() {
        assertNotNull(validateSecretNameMappings(listOf(pair("A", "B=C"))))
        assertNotNull(validateSecretNameMappings(listOf(pair("A", "B\nC"))))
        assertNotNull(validateSecretNameMappings(listOf(pair("A", "B\u0000"))))
    }

    @Test fun `new names follow the same validation as edited secret names`() {
        val names =
            listOf("valid_NAME9", "path/name:part.ext-key", "9KEY", "HAS SPACE", " NAME", "NAME ", "HAS=EQUALS", "KEY@HOST", "caf\u00e9") +
                DopplerReservedSecrets.NAMES
        for (name in names) {
            val acceptedByEditor =
                try {
                    SecretsDocumentFormat.parse("\"$name\": synthetic")
                    true
                } catch (_: SecretsFormatException) {
                    false
                }
            assertEquals(name, acceptedByEditor, validateSecretNameMappings(listOf(pair("SOURCE", name))) == null)
            val secrets = mapOf("SOURCE" to "synthetic")
            assertEquals(
                name,
                if (acceptedByEditor) mapOf(name to "synthetic") else secrets,
                applySecretNameMapping(secrets, listOf(pair("SOURCE", name))),
            )
        }
    }

    @Test fun `invalid name errors do not echo potentially sensitive input`() {
        val invalid = "synthetic pasted value"
        val error = validateSecretNameMappings(listOf(pair("SOURCE", invalid)))!!
        assertTrue(!error.contains(invalid))
    }

    @Test fun `duplicate current or new names are rejected`() {
        assertNotNull(validateSecretNameMappings(listOf(pair("A", "X"), pair("A", "Y"))))
        assertNotNull(validateSecretNameMappings(listOf(pair("A", "X"), pair("B", "X"))))
    }
}
