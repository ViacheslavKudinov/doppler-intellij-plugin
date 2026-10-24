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

    @Test
    fun `corrected save can retry after confirmed writes precede a rejected edit`() {
        val remote = linkedMapOf<String, String?>("OLD_NAME" to "synthetic", "KEEP" to "original")
        var baseline: Map<String, String?> = remote.toMap()
        try {
            applyChangesWithOptimisticLock(
                expectedSnapshot = baseline,
                changes = SecretChanges(linkedMapOf("KEEP" to "updated", "NEW_NAME" to "rejected"), setOf("OLD_NAME")),
                checkAuthContext = {},
                loadSnapshot = { remote.toMap() },
                deleteSecrets = { names -> names.forEach(remote::remove) },
                setSecret = { name, value ->
                    if (name == "NEW_NAME") throw DopplerCliException("Synthetic rejection")
                    remote[name] = value
                },
                onSnapshotAdvanced = { baseline = it },
            )
            fail("Expected rejected write")
        } catch (_: DopplerCliException) {
            assertEquals(mapOf("OLD_NAME" to "synthetic", "KEEP" to "updated"), baseline)
            assertEquals(baseline, remote)
        }

        applyChangesWithOptimisticLock(
            expectedSnapshot = baseline,
            changes = SecretChanges(mapOf("NEW_NAME" to "corrected"), setOf("OLD_NAME")),
            checkAuthContext = {},
            loadSnapshot = { remote.toMap() },
            deleteSecrets = { names ->
                assertEquals("corrected", remote["NEW_NAME"])
                names.forEach(remote::remove)
            },
            setSecret = { name, value -> remote[name] = value },
            onSnapshotAdvanced = { baseline = it },
        )
        assertEquals(mapOf("KEEP" to "updated", "NEW_NAME" to "corrected"), remote)
        assertEquals(remote, baseline)
    }

    @Test
    fun `TESTq rejection preserves TEST and corrected TESTQ rename succeeds`() {
        val remote = linkedMapOf<String, String?>("TEST" to "synthetic")
        var baseline: Map<String, String?> = remote.toMap()
        expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = baseline,
                changes = SecretChanges.between(baseline, com.vk.doppler.editor.SecretsDocumentFormat.parse("TESTq: synthetic")),
                checkAuthContext = {},
                loadSnapshot = { remote.toMap() },
                deleteSecrets = { fail("Must not delete before a replacement succeeds") },
                setSecret = { name, _ ->
                    assertEquals("TESTq", name)
                    throw DopplerCliException("Synthetic Classic naming rejection")
                },
                onSnapshotAdvanced = { fail("A rejected write must not advance the baseline") },
            )
        }
        assertEquals(mapOf("TEST" to "synthetic"), remote)
        assertEquals(baseline, remote)

        val writes = mutableListOf<String>()
        applyChangesWithOptimisticLock(
            expectedSnapshot = baseline,
            changes = SecretChanges.between(baseline, com.vk.doppler.editor.SecretsDocumentFormat.parse("TESTQ: synthetic")),
            checkAuthContext = {},
            loadSnapshot = { remote.toMap() },
            deleteSecrets = { names ->
                assertEquals("synthetic", remote["TESTQ"])
                writes += "delete"
                names.forEach(remote::remove)
            },
            setSecret = { name, value ->
                writes += "set"
                remote[name] = value
            },
            onSnapshotAdvanced = { baseline = it },
        )
        assertEquals(listOf("set", "delete"), writes)
        assertEquals(mapOf("TESTQ" to "synthetic"), remote)
        assertEquals(remote, baseline)
    }

    @Test
    fun `later rejected replacement preserves all originals despite earlier successful writes`() {
        val remote = linkedMapOf<String, String?>("FIRST" to "synthetic-first", "SECOND" to "synthetic-second")
        var baseline: Map<String, String?> = remote.toMap()
        expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = baseline,
                changes = SecretChanges(
                    linkedMapOf("NEW_FIRST" to "synthetic-first", "NEW_SECOND" to "synthetic-second"),
                    setOf("FIRST", "SECOND"),
                ),
                checkAuthContext = {},
                loadSnapshot = { remote.toMap() },
                deleteSecrets = { fail("Must not delete unless all replacements succeed") },
                setSecret = { name, value ->
                    if (name == "NEW_SECOND") throw DopplerCliException("Synthetic rejection")
                    remote[name] = value
                },
                onSnapshotAdvanced = { baseline = it },
            )
        }
        assertEquals(mapOf("FIRST" to "synthetic-first", "SECOND" to "synthetic-second", "NEW_FIRST" to "synthetic-first"), remote)
        assertEquals(remote, baseline)
    }

    @Test
    fun `remote changes after partial success still block the retry`() {
        val remote = linkedMapOf<String, String?>("A" to "old", "B" to "old-b")
        var baseline: Map<String, String?> = remote.toMap()
        expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = baseline,
                changes = SecretChanges(linkedMapOf("A" to "mine", "B" to "rejected"), emptySet()),
                checkAuthContext = {},
                loadSnapshot = { remote.toMap() },
                deleteSecrets = { fail("No deletion expected") },
                setSecret = { name, value ->
                    if (name == "B") throw DopplerCliException("Synthetic rejection")
                    remote[name] = value
                },
                onSnapshotAdvanced = { baseline = it },
            )
        }
        assertEquals(mapOf("A" to "mine", "B" to "old-b"), baseline)
        remote["TEAMMATE"] = "external"
        val error = expectConflict {
            applyChangesWithOptimisticLock(
                expectedSnapshot = baseline,
                changes = SecretChanges(mapOf("B" to "corrected"), emptySet()),
                checkAuthContext = {},
                loadSnapshot = { remote.toMap() },
                deleteSecrets = { fail("Must not delete after conflict") },
                setSecret = { _, _ -> fail("Must not write after conflict") },
                onSnapshotAdvanced = { fail("Must not advance after conflict") },
            )
        }
        assertEquals(DopplerCliException.Kind.REMOTE_CHANGED, error.kind)
        assertEquals("external", remote["TEAMMATE"])
    }
}
