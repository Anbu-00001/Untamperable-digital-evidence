package com.realitylock.app.places

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Is the address layer real? This asks the app's own [AddressResolver], on a real
 * phone and over the real network, to name places it has never been told about.
 *
 * It exists to rule out the two ways such a feature can quietly be fake: a lookup
 * that is really hard-wired to the demo coordinates (so world landmarks would come
 * back as "Tambaram"), and one that invents a name for a place that has none (so a
 * point in the middle of the Atlantic would come back with a label).
 *
 * Needs a network, so it is run by hand — `am instrument -e class ...` — and not
 * as part of the offline suites. It uses a throwaway cache directory, so a cached
 * answer cannot stand in for a lookup.
 */
class AddressResolverInstrumentedTest {

    private lateinit var resolver: AddressResolver
    private lateinit var cache: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cache = File(context.cacheDir, "place-verify-${System.nanoTime()}").also { it.mkdirs() }
        resolver = AddressResolver(context, OkHttpClient(), cache)
    }

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private fun lookup(label: String, lat: Double, lon: Double): PlaceName? = runBlocking {
        resolver.resolve(lat, lon).also {
            Log.i(TAG, "$label ($lat, $lon) -> ${it?.short} | ${it?.full} | ${it?.source}")
        }
    }

    @Test
    fun worldLandmarksAreNamedForWhatTheyAre() {
        val cases = listOf(
            Triple("Big Ben", 51.5007 to -0.1246, listOf("london", "westminster")),
            Triple("Eiffel Tower", 48.8584 to 2.2945, listOf("paris")),
            Triple("Tokyo Tower", 35.6586 to 139.7454, listOf("tokyo", "minato")),
        )
        for ((label, point, expected) in cases) {
            val place = lookup(label, point.first, point.second)
            assertNotNull("no place returned for $label", place)
            val text = "${place!!.short} ${place.full}".lowercase()
            assertTrue("$label came back as '${place.short}' / '${place.full}'", expected.any { it in text })
        }
    }

    @Test
    fun theMiddleOfTheAtlanticGetsNoNameAtAll() {
        // 0 N, 30 W: open ocean. An honest geocoder has nothing to say, and the app
        // must then show coordinates alone rather than make a label up.
        assertNull(lookup("mid-Atlantic", 0.0, -30.0))
    }

    private companion object {
        const val TAG = "PlaceVerify"
    }
}
