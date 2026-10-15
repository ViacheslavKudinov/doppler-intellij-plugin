package com.vk.doppler.completion

internal data class SecretReferenceContext(val prefix: String)

/** What to do after a reference was completed: drop leftovers, add `}` and place the caret. */
internal data class ReferenceClosing(val removeUntil: Int, val insertBrace: Boolean, val caretOffset: Int)

/** Text-only helpers for `${...}` references, kept free of IDE types so they can be unit tested. */
internal object SecretReferenceParser {

    private val KEY = Regex("^\"?([A-Za-z0-9_/:.-]+)\"?:(?=\\s|$)", RegexOption.MULTILINE)

    /**
     * The reference being typed at [caretOffset], or `null` when the caret is not inside an
     * unclosed `${` on its own line. Whitespace is not allowed inside the braces.
     */
    fun contextAt(text: CharSequence, caretOffset: Int): SecretReferenceContext? {
        if (caretOffset !in 0..text.length) return null
        var lineStart = caretOffset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

        var first = lineStart
        while (first < caretOffset && (text[first] == ' ' || text[first] == '\t')) first++
        if (first < caretOffset && text[first] == '#') return null

        var open = caretOffset - 2
        while (open >= lineStart && !(text[open] == '$' && text[open + 1] == '{')) open--
        if (open < lineStart) return null

        val prefixStart = open + 2
        for (index in prefixStart until caretOffset) {
            val char = text[index]
            if (char.isWhitespace() || char == '{' || char == '}' || char == '$') return null
        }
        return SecretReferenceContext(text.subSequence(prefixStart, caretOffset).toString())
    }

    /** Offset of the `}` that closes a reference continuing at [from], if the rest is one token. */
    fun closingBraceOffset(text: CharSequence, from: Int): Int? {
        for (index in from until text.length) {
            val char = text[index]
            if (char == '}') return index
            if (char.isWhitespace() || char == '{' || char == '$') return null
        }
        return null
    }

    fun closingAfter(text: CharSequence, tailOffset: Int): ReferenceClosing {
        val close = closingBraceOffset(text, tailOffset)
        return if (close != null) {
            ReferenceClosing(removeUntil = close, insertBrace = false, caretOffset = tailOffset + 1)
        } else {
            ReferenceClosing(removeUntil = tailOffset, insertBrace = true, caretOffset = tailOffset + 1)
        }
    }

    /** Top-level secret names of the editor document, read without a full YAML parse. */
    fun secretNames(text: CharSequence): Set<String> =
        KEY.findAll(text).mapTo(linkedSetOf()) { it.groupValues[1] }
}
