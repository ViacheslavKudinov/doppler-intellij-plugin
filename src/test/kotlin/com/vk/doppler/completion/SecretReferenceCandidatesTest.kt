package com.vk.doppler.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretReferenceCandidatesTest {

    private val current = SecretReferenceLocation("app", "api")

    private val index: SecretNames = mapOf(
        "app" to mapOf(
            "api" to setOf("OLD_SERVER_COPY"),
            "worker" to setOf("QUEUE", "REDIS"),
        ),
        "other" to mapOf("backend" to setOf("DATABASE_URL")),
    )

    private fun find(prefix: String, live: Collection<String> = listOf("LOCAL"), names: SecretNames = index) =
        SecretReferenceCandidates.find(current, live, names, prefix)

    private fun references(prefix: String, live: Collection<String> = listOf("LOCAL")) =
        find(prefix, live).map { it.reference }.toSet()

    @Test
    fun `offers the three reference shapes without an environment`() {
        assertEquals(
            setOf("LOCAL", "worker.QUEUE", "worker.REDIS", "other.backend.DATABASE_URL"),
            references(""),
        )
    }

    @Test
    fun `no reference has more than project config and secret segments`() {
        val longest = find("").maxOf { it.reference.split('.').size }

        assertEquals(3, longest)
    }

    @Test
    fun `the current config comes from the live document, not the index`() {
        assertTrue(references("").contains("LOCAL"))
        assertFalse(references("").contains("OLD_SERVER_COPY"))
        assertEquals(setOf("FRESH"), references("", live = listOf("FRESH")).filter { !it.contains('.') }.toSet())
    }

    @Test
    fun `scopes carry priorities so closer references rank first`() {
        val byReference = find("").associateBy { it.reference }

        assertEquals(SecretReferenceScope.CONFIG, byReference.getValue("LOCAL").scope)
        assertEquals(SecretReferenceScope.PROJECT, byReference.getValue("worker.QUEUE").scope)
        assertEquals(SecretReferenceScope.ACCOUNT, byReference.getValue("other.backend.DATABASE_URL").scope)
        assertTrue(SecretReferenceScope.CONFIG.priority > SecretReferenceScope.PROJECT.priority)
        assertTrue(SecretReferenceScope.PROJECT.priority > SecretReferenceScope.ACCOUNT.priority)
    }

    @Test
    fun `candidates say where they come from`() {
        val byReference = find("").associateBy { it.reference }

        assertEquals("app/api", byReference.getValue("LOCAL").origin)
        assertEquals("app/worker", byReference.getValue("worker.QUEUE").origin)
        assertEquals("other/backend", byReference.getValue("other.backend.DATABASE_URL").origin)
    }

    @Test
    fun `filters by what has been typed across all segments`() {
        assertEquals(setOf("worker.QUEUE", "worker.REDIS"), references("wor"))
        assertEquals(setOf("worker.REDIS"), references("worker.R"))
        assertEquals(setOf("other.backend.DATABASE_URL"), references("other.b"))
        assertEquals(setOf("other.backend.DATABASE_URL"), references("other.backend.DA"))
        assertEquals(setOf("LOCAL"), references("LO"))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(setOf("LOCAL"), references("lo"))
        assertEquals(setOf("worker.QUEUE"), references("WORKER.q"))
    }

    @Test
    fun `nothing matches an unknown prefix`() {
        assertEquals(emptySet<String>(), references("nope"))
        assertEquals(emptySet<String>(), references("worker.NOPE"))
        assertEquals(emptySet<String>(), references("other.backend.DATABASE_URL.extra"))
    }

    @Test
    fun `an exact reference still matches itself`() {
        assertEquals(setOf("worker.QUEUE"), references("worker.QUEUE"))
    }

    @Test
    fun `the fully qualified form of the current project is offered once typed`() {
        assertEquals(
            setOf("app.api.LOCAL", "app.worker.QUEUE", "app.worker.REDIS"),
            references("app."),
        )
        assertEquals(setOf("app.worker.QUEUE", "app.worker.REDIS"), references("app.worker."))
        assertEquals(setOf("app.api.LOCAL"), references("app.api."))
    }

    @Test
    fun `the qualified current project form uses live names for the current config`() {
        assertFalse(references("app.api.").contains("app.api.OLD_SERVER_COPY"))
    }

    @Test
    fun `the qualified form is not offered before the project is typed`() {
        assertTrue(references("").none { it.startsWith("app.") })
        assertTrue(references("a").none { it.startsWith("app.") })
    }

    @Test
    fun `a config name shared with another project stays project qualified`() {
        val shared: SecretNames = mapOf("other" to mapOf("api" to setOf("TOKEN")))

        assertEquals(setOf("other.api.TOKEN"), SecretReferenceCandidates.find(current, emptyList(), shared, "").map { it.reference }.toSet())
        assertEquals(emptyList<SecretReferenceCandidate>(), SecretReferenceCandidates.find(current, emptyList(), shared, "api."))
    }

    @Test
    fun `an empty index still offers the current config`() {
        assertEquals(setOf("LOCAL"), find("", names = emptyMap()).map { it.reference }.toSet())
    }

    @Test
    fun `an empty everything offers nothing`() {
        assertEquals(emptyList<SecretReferenceCandidate>(), find("", live = emptyList(), names = emptyMap()))
    }

    @Test
    fun `references are never duplicated`() {
        listOf("", "app.", "app.worker.", "w").forEach { prefix ->
            val found = find(prefix).map { it.reference }
            assertEquals("duplicates for '$prefix'", found.size, found.toSet().size)
        }
    }

    @Test
    fun `no candidate contains whitespace`() {
        assertTrue(find("").none { candidate -> candidate.reference.any { it.isWhitespace() } })
    }
}
