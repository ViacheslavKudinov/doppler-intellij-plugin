package com.vk.doppler.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretReferenceCompletionTest {

    @Test
    fun `offers all four reference scopes`() {
        val current = SecretReferenceSource("app", "dev", "api", setOf("LOCAL"))
        val sources = listOf(
            current,
            SecretReferenceSource("app", "dev", "worker", setOf("QUEUE")),
            SecretReferenceSource("app", "prod", "api", setOf("TOKEN")),
            SecretReferenceSource("other", "prod", "backend", setOf("DATABASE_URL")),
        )

        assertEquals(
            listOf("LOCAL", "worker.QUEUE", "prod.api.TOKEN", "other.prod.backend.DATABASE_URL"),
            SecretReferenceCompletion.candidates(current, sources, ""),
        )
    }

    @Test
    fun `filters candidates by the reference prefix`() {
        val current = SecretReferenceSource("app", "dev", "api", emptySet())
        val sources = listOf(
            SecretReferenceSource("app", "dev", "worker", setOf("QUEUE", "REDIS")),
        )

        assertEquals(
            listOf("worker.REDIS"),
            SecretReferenceCompletion.candidates(current, sources, "worker.R"),
        )
    }

    @Test
    fun `offers completion only inside an unclosed reference without whitespace`() {
        assertEquals(
            SecretReferenceContext("app.dev"),
            SecretReferenceCompletion.contextAt("TOKEN: \${app.dev", "TOKEN: \${app.dev".length),
        )
        assertNull(SecretReferenceCompletion.contextAt("TOKEN: \${app dev", "TOKEN: \${app dev".length))
        assertNull(SecretReferenceCompletion.contextAt("TOKEN: \${app}", "TOKEN: \${app}".length))
        assertNull(SecretReferenceCompletion.contextAt("# \${app", "# \${app".length))
    }
}
