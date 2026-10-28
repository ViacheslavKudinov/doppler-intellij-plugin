package com.vk.doppler.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val REF = "\${"

class SecretReferenceParserTest {
    /** The caret is marked with `|` in [marked]. */
    private fun prefixAt(marked: String): String? {
        val caret = marked.indexOf('|')
        require(caret >= 0) { "missing caret marker" }
        return SecretReferenceParser.contextAt(marked.removeRange(caret, caret + 1), caret)?.prefix
    }

    @Test
    fun `an empty prefix right after the opening`() {
        assertEquals("", prefixAt("TOKEN: $REF|"))
    }

    @Test
    fun `reads each supported reference shape`() {
        assertEquals("SECRET", prefixAt("A: ${REF}SECRET|"))
        assertEquals("config.SECRET", prefixAt("A: ${REF}config.SECRET|"))
        assertEquals("project.config.SECRET", prefixAt("A: ${REF}project.config.SECRET|"))
    }

    @Test
    fun `only the text before the caret is the prefix`() {
        assertEquals("app", prefixAt("A: ${REF}app|.config}"))
        assertEquals("app", prefixAt("A: ${REF}app|}"))
    }

    @Test
    fun `the innermost unclosed reference wins`() {
        assertEquals("y", prefixAt("A: ${REF}x} ${REF}y|"))
        assertEquals("b", prefixAt("A: ${REF}a${REF}b|"))
    }

    @Test
    fun `no completion outside a reference`() {
        assertNull(prefixAt("TOKEN: abc|"))
        assertNull(prefixAt("TOKEN: ${REF}app}|"))
        assertNull(prefixAt("TOKEN: $|"))
        assertNull(prefixAt("TOKEN: |${REF}app"))
        assertNull(prefixAt("|"))
    }

    @Test
    fun `no whitespace is allowed inside the braces`() {
        assertNull(prefixAt("A: ${REF}ap p|"))
        assertNull(prefixAt("A: ${REF}ap\tp|"))
        assertNull(prefixAt("A: $REF |"))
    }

    @Test
    fun `braces and dollars inside the reference end completion`() {
        assertNull(prefixAt("A: ${REF}a{b|"))
        assertNull(prefixAt("A: ${REF}a\$b|"))
        assertNull(prefixAt("A: ${REF}a}b|"))
    }

    @Test
    fun `an opening on a previous line does not leak into the next line`() {
        assertNull(prefixAt("A: ${REF}abc\nB: val|"))
        assertNull(prefixAt("A: ${REF}abc\n|"))
        assertEquals("x", prefixAt("A: ${REF}abc\nB: ${REF}x|"))
    }

    @Test
    fun `comment lines are skipped`() {
        assertNull(prefixAt("# ${REF}app|"))
        assertNull(prefixAt("   # ${REF}app|"))
        assertNull(prefixAt("\t#${REF}app|"))
        assertNull(prefixAt("A: value\n \t# ${REF}app|"))
    }

    @Test
    fun `indented references after comment lines are completed`() {
        assertEquals("app", prefixAt("# comment\n \tA: ${REF}app|"))
        assertEquals("", prefixAt("# comment\n \t$REF|"))
    }

    @Test
    fun `a hash inside a value is not a comment`() {
        assertEquals("app", prefixAt("KEY: a#b ${REF}app|"))
    }

    @Test
    fun `block scalar continuation lines are completed too`() {
        val text = "KEY: |-\n  first ${REF}app"

        assertEquals("app", SecretReferenceParser.contextAt(text, text.length)?.prefix)
    }

    @Test
    fun `out of range carets are rejected`() {
        assertNull(SecretReferenceParser.contextAt("abc", -1))
        assertNull(SecretReferenceParser.contextAt("abc", 4))
        assertNull(SecretReferenceParser.contextAt("", 0))
    }

    @Test
    fun `only the current line is searched in a large document`() {
        val text =
            buildString {
                append("A: ${REF}open\n")
                repeat(20_000) { append("KEY_$it: value\n") }
                append("LAST: ${REF}ab")
            }
        assertEquals("ab", SecretReferenceParser.contextAt(text, text.length)?.prefix)
        assertNull(SecretReferenceParser.contextAt(text, text.length - "LAST: ${REF}ab".length))
    }

    @Test
    fun `finds a closing brace only when the rest is a single token`() {
        assertEquals(2, SecretReferenceParser.closingBraceOffset("ab}", 0))
        assertEquals(0, SecretReferenceParser.closingBraceOffset("}", 0))
        assertEquals(4, SecretReferenceParser.closingBraceOffset("a: b}c}", 3))
        assertNull(SecretReferenceParser.closingBraceOffset("ab cd}", 0))
        assertNull(SecretReferenceParser.closingBraceOffset("a{b}", 0))
        assertNull(SecretReferenceParser.closingBraceOffset("a${REF}b}", 0))
        assertNull(SecretReferenceParser.closingBraceOffset("ab", 0))
        assertNull(SecretReferenceParser.closingBraceOffset("ab\n}", 0))
    }

    @Test
    fun `an unclosed reference gets a brace and the caret moves past it`() {
        val text = "A: ${REF}app.api.TOKEN"
        val closing = SecretReferenceParser.closingAfter(text, text.length)

        assertEquals(ReferenceClosing(removeUntil = text.length, insertBrace = true, caretOffset = text.length + 1), closing)
    }

    @Test
    fun `an existing brace is reused instead of duplicated`() {
        val text = "A: ${REF}app.api.TOKEN}"
        val tail = text.length - 1

        assertEquals(
            ReferenceClosing(removeUntil = tail, insertBrace = false, caretOffset = tail + 1),
            SecretReferenceParser.closingAfter(text, tail),
        )
    }

    @Test
    fun `leftovers of the replaced reference are removed up to the brace`() {
        val text = "A: ${REF}worker.QUEUE.old} rest"
        val tail = "A: ${REF}worker.QUEUE".length

        val closing = SecretReferenceParser.closingAfter(text, tail)

        assertEquals(text.indexOf('}'), closing.removeUntil)
        assertEquals(false, closing.insertBrace)
        assertEquals(tail + 1, closing.caretOffset)
    }

    @Test
    fun `text after the reference is never swallowed`() {
        val text = "A: ${REF}worker.QUEUE and more}"
        val tail = "A: ${REF}worker.QUEUE".length

        val closing = SecretReferenceParser.closingAfter(text, tail)

        assertEquals(tail, closing.removeUntil)
        assertEquals(true, closing.insertBrace)
    }

    @Test
    fun `reads top level secret names from the document`() {
        val document =
            """
            #################################################################
            ##  This file was generated by Doppler.                        ##
            #################################################################

            API_KEY: abc
            "WITH:COLON": value
            EMPTY:
            URL: psql://app@localhost/appdb
            PRIVATE_KEY: |-
              nested: not a secret
              -----BEGIN-----
            LAST: v
            """.trimIndent()

        assertEquals(
            setOf("API_KEY", "WITH:COLON", "EMPTY", "URL", "PRIVATE_KEY", "LAST"),
            SecretReferenceParser.secretNames(document),
        )
    }

    @Test
    fun `ignores comments and indented or malformed lines`() {
        val document = "# KEY: v\n  INDENTED: v\nnot a key\nA B: v\nOK: v\n"

        assertEquals(setOf("OK"), SecretReferenceParser.secretNames(document))
    }

    @Test
    fun `handles windows line endings and unsaved edits`() {
        assertEquals(setOf("A", "B"), SecretReferenceParser.secretNames("A: 1\r\nB:\r\nC"))
        assertEquals(setOf("A"), SecretReferenceParser.secretNames("A: \${"))
    }

    @Test
    fun `an empty document has no names`() {
        assertEquals(emptySet<String>(), SecretReferenceParser.secretNames(""))
    }
}
