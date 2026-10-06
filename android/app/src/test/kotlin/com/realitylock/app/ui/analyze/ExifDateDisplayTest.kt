package com.realitylock.app.ui.analyze

import org.junit.Assert.assertEquals
import org.junit.Test

/** The capture date is shown as the file wrote it; only the separators are tidied. */
class ExifDateDisplayTest {

    @Test
    fun `colon-separated EXIF date becomes dashed, time untouched`() {
        assertEquals("2026-10-05 20:40:50", exifDateForDisplay("2026:10:05 20:40:50"))
    }

    @Test
    fun `anything that is not the EXIF shape is shown unchanged`() {
        assertEquals("yesterday-ish", exifDateForDisplay("yesterday-ish"))
        assertEquals("2026-10-05T20:40:50", exifDateForDisplay("2026-10-05T20:40:50"))
    }
}
