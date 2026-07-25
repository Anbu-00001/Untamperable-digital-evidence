package com.realitylock.app.certificate

/**
 * Greedy word wrap for the PDF renderer.
 *
 * Takes a `measure` function rather than an `android.graphics.Paint`, so the
 * wrapping algorithm — the part that can actually be wrong — is unit-tested on
 * the JVM with a trivial measurer, while the renderer passes
 * `paint::measureText`.
 */
object TextWrapper {

    /**
     * Splits [text] into lines no wider than [maxWidth].
     *
     * A single word longer than [maxWidth] (a 64-character SHA-256 hex digest, for
     * instance) is broken mid-word rather than allowed to overflow the page — an
     * unbroken hash running off the margin would silently truncate the one value a
     * reader most needs to compare.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (maxWidth <= 0f) return listOf(text)

        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
                continue
            }
            if (current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder()
            }
            // The word alone may still not fit.
            if (measure(word) <= maxWidth) {
                current = StringBuilder(word)
            } else {
                val chunks = breakLongWord(word, maxWidth, measure)
                lines.addAll(chunks.dropLast(1))
                current = StringBuilder(chunks.last())
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines.ifEmpty { listOf("") }
    }

    /** Splits an unbreakable token into the widest chunks that fit. */
    private fun breakLongWord(word: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = start + 1
            while (end < word.length && measure(word.substring(start, end + 1)) <= maxWidth) {
                end += 1
            }
            chunks.add(word.substring(start, end))
            start = end
        }
        return chunks.ifEmpty { listOf(word) }
    }
}
