package com.vk.doppler.service

import com.vk.doppler.editor.ParsedSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun edited(values: Map<String, String?>, cleared: Set<String> = emptySet()) =
    ParsedSecrets(values, cleared)

class SecretChangesTest {

    @Test
    fun `new null or cleared entries are identified as unvalued additions`() {
        val edited = ParsedSecrets(
            values = mapOf("NULL_VALUE" to null, "EMPTY_VALUE" to ""),
            cleared = setOf("BLANK_VALUE"),
        )

        assertEquals(
            setOf("NULL_VALUE", "BLANK_VALUE"),
            SecretChanges.unvaluedAdditions(emptyMap(), edited),
        )
    }

    @Test
    fun `restricted secrets and explicit empty values are not unvalued additions`() {
        val edited = ParsedSecrets(
            values = mapOf("RESTRICTED" to null, "EMPTY_VALUE" to ""),
            cleared = setOf("REMOVED"),
        )

        assertTrue(
            SecretChanges.unvaluedAdditions(
                original = mapOf("RESTRICTED" to null, "REMOVED" to "old"),
                edited = edited,
            ).isEmpty(),
        )
    }

    @Test
    fun `no edits produce no writes`() {
        val original = mapOf("A" to "1", "B" to "2")
        val changes = SecretChanges.between(original, edited(original))
        assertTrue(changes.isEmpty)
        assertEquals(0, changes.totalOperations)
    }

    @Test
    fun `only changed keys are pushed`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1", "B" to "2", "C" to "3"),
            edited = edited(mapOf("A" to "1", "B" to "changed", "C" to "3")),
        )
        assertEquals(mapOf("B" to "changed"), changes.upserted)
        assertEquals(emptySet<String>(), changes.removed)
    }

    @Test
    fun `added and removed keys are detected`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1", "OLD" to "x"),
            edited = edited(mapOf("A" to "1", "NEW" to "y")),
        )
        assertEquals(mapOf("NEW" to "y"), changes.upserted)
        assertEquals(setOf("OLD"), changes.removed)
    }

    @Test
    fun `setting an empty string is an update rather than a delete`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1"),
            edited = edited(mapOf("A" to "")),
        )
        assertEquals(mapOf("A" to ""), changes.upserted)
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `successful snapshot retains readable values left as null placeholders`() {
        val original = mapOf("A" to "old", "UNCHANGED" to "readable", "RESTRICTED" to null, "REMOVED" to "value")
        val changes = SecretChanges.between(
            original,
            edited(mapOf("A" to "new", "UNCHANGED" to null, "RESTRICTED" to null)),
        )

        assertEquals(
            mapOf("A" to "new", "UNCHANGED" to "readable", "RESTRICTED" to null),
            changes.applyTo(original),
        )
    }

    @Test
    fun `emptying the document removes everything`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1", "B" to "2"),
            edited = edited(emptyMap()),
        )
        assertTrue(changes.upserted.isEmpty())
        assertEquals(setOf("A", "B"), changes.removed)
    }

    @Test
    fun `an untouched restricted secret is not written back`() {
        val changes = SecretChanges.between(
            original = mapOf("RESTRICTED" to null, "A" to "1"),
            edited = edited(mapOf("RESTRICTED" to null, "A" to "1")),
        )
        assertTrue(changes.isEmpty)
    }

    @Test
    fun `a restricted secret can be overwritten`() {
        val changes = SecretChanges.between(
            original = mapOf("RESTRICTED" to null),
            edited = edited(mapOf("RESTRICTED" to "new")),
        )
        assertEquals(mapOf("RESTRICTED" to "new"), changes.upserted)
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `clearing a readable secret deletes it`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1", "B" to "2"),
            edited = edited(mapOf("B" to "2"), cleared = setOf("A")),
        )
        assertTrue(changes.upserted.isEmpty())
        assertEquals(setOf("A"), changes.removed)
    }

    @Test
    fun `clearing a restricted secret deletes it`() {
        val changes = SecretChanges.between(
            original = mapOf("RESTRICTED" to null),
            edited = edited(emptyMap(), cleared = setOf("RESTRICTED")),
        )
        assertTrue(changes.upserted.isEmpty())
        assertEquals(setOf("RESTRICTED"), changes.removed)
    }

    @Test
    fun `clearing a key that never existed is ignored`() {
        val changes = SecretChanges.between(
            original = mapOf("A" to "1"),
            edited = edited(mapOf("A" to "1"), cleared = setOf("BRAND_NEW")),
        )
        assertTrue(changes.isEmpty)
    }
}
