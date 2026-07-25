package com.realitylock.app.certificate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word wrapping for the PDF, with a trivial measurer (one unit per character) so
 * the algorithm is tested rather than a font.
 */
class TextWrapperTest {

    /** Every character is one unit wide. */
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `short text stays on one line`() {
        assertEquals(listOf("hello world"), TextWrapper.wrap("hello world", 20f, measure))
    }

    @Test
    fun `text wraps at word boundaries`() {
        val lines = TextWrapper.wrap("the quick brown fox jumps", 10f, measure)

        assertEquals(listOf("the quick", "brown fox", "jumps"), lines)
        // No line may exceed the budget.
        assertTrue(lines.all { measure(it) <= 10f })
    }

    @Test
    fun `a hash longer than the line is broken rather than overflowing`() {
        // The real case: a 64-character SHA-256 digest in a narrow column. Letting
        // it overflow would silently truncate the one value a reader most needs to
        // compare against their own copy.
        val hash = "a".repeat(64)

        val lines = TextWrapper.wrap(hash, 16f, measure)

        assertEquals(4, lines.size)
        assertTrue(lines.all { it.length <= 16 })
        // Nothing is lost in the process.
        assertEquals(hash, lines.joinToString(""))
    }

    @Test
    fun `a long word after normal words breaks without losing the words`() {
        val lines = TextWrapper.wrap("root ${"f".repeat(20)}", 10f, measure)

        assertEquals("root", lines.first())
        assertEquals("f".repeat(20), lines.drop(1).joinToString(""))
    }

    @Test
    fun `empty text yields one empty line rather than nothing`() {
        // Returning an empty list would make the caller advance the cursor by zero
        // and silently overdraw the next line on top of this one.
        assertEquals(listOf(""), TextWrapper.wrap("", 100f, measure))
    }

    @Test
    fun `a non-positive width returns the text rather than looping forever`() {
        assertEquals(listOf("text"), TextWrapper.wrap("text", 0f, measure))
        assertEquals(listOf("text"), TextWrapper.wrap("text", -5f, measure))
    }

    @Test
    fun `a word exactly the line width is not broken`() {
        assertEquals(listOf("abcde"), TextWrapper.wrap("abcde", 5f, measure))
    }
}
