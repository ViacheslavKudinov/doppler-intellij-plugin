package com.vk.doppler.service

import com.vk.doppler.cli.DopplerCliException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DopplerOptimisticLockTest {

    @Test
    fun `remote change before save aborts without writing`() {
        val writes = mutableListOf<String>()

        val error = expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = mapOf("API_KEY" to "old"),
                changes = SecretChanges(mapOf("API_KEY" to "mine"), emptySet()),
                checkAuthContext = {},
                loadSnapshot = { mapOf("API_KEY" to "teammate-change") },
                deleteSecrets = { writes += "delete" },
                setSecret = { _, _ -> writes += "set" },
            )
        }

        assertEquals(DopplerCliException.Kind.REMOTE_CHANGED, error.kind)
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `multi-operation save performs one preflight read`() {
        val remote = linkedMapOf<String, String?>("A" to "old-a", "B" to "old-b", "C" to "old-c")
        val writes = mutableListOf<String>()
        var reads = 0
        var accountChecks = 0

        applyChangesWithOptimisticLock(
            expectedSnapshot = remote.toMap(),
            changes = SecretChanges(
                upserted = linkedMapOf("A" to "new-a", "B" to "new-b"),
                removed = emptySet(),
            ),
            checkAuthContext = { accountChecks++ },
            loadSnapshot = {
                assertEquals(1, accountChecks)
                reads++
                remote.toMap()
            },
            deleteSecrets = { names -> names.forEach(remote::remove) },
            setSecret = { name, value ->
                writes += name
                remote[name] = value
            },
        )

        assertEquals(1, reads)
        assertEquals(1, accountChecks)
        assertEquals(listOf("A", "B"), writes)
    }

    @Test
    fun `unchanged remote state permits the complete diff`() {
        val remote = linkedMapOf<String, String?>("A" to "old", "REMOVE" to "value")

        applyChangesWithOptimisticLock(
            expectedSnapshot = remote.toMap(),
            changes = SecretChanges(mapOf("A" to "new", "ADDED" to "value"), setOf("REMOVE")),
            checkAuthContext = {},
            loadSnapshot = { remote.toMap() },
            deleteSecrets = { names -> names.forEach(remote::remove) },
            setSecret = { name, value -> remote[name] = value },
        )

        assertEquals(mapOf("A" to "new", "ADDED" to "value"), remote)
    }

    @Test
    fun `restricted secret can be overwritten after preflight`() {
        val remote = linkedMapOf<String, String?>("RESTRICTED" to null)

        applyChangesWithOptimisticLock(
            expectedSnapshot = remote.toMap(),
            changes = SecretChanges(mapOf("RESTRICTED" to "new-value"), emptySet()),
            checkAuthContext = {},
            loadSnapshot = { remote.toMap() },
            deleteSecrets = { names -> names.forEach(remote::remove) },
            setSecret = { name, _ -> remote[name] = null },
        )

        assertEquals(mapOf("RESTRICTED" to null), remote)
    }

    @Test
    fun `account mismatch prevents snapshot reads and all writes`() {
        val error = expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = mapOf("OLD" to "value"),
                changes = SecretChanges(mapOf("NEW" to "value"), setOf("OLD")),
                checkAuthContext = {
                    throw DopplerCliException("Account changed", DopplerCliException.Kind.AUTH_CONTEXT_CHANGED)
                },
                loadSnapshot = { throw AssertionError("Must not read another account's secrets") },
                deleteSecrets = { throw AssertionError("Must not delete") },
                setSecret = { _, _ -> throw AssertionError("Must not write") },
            )
        }
        assertEquals(DopplerCliException.Kind.AUTH_CONTEXT_CHANGED, error.kind)
    }

    private fun expectConflict(block: () -> Unit): DopplerCliException = try {
        block()
        fail("Expected a remote-change conflict")
        throw AssertionError("unreachable")
    } catch (error: DopplerCliException) {
        error
    }
}
