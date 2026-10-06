package com.realitylock.app.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The place name is a third party's statement, so what these pin is that we only
 * ever echo what a service actually said — never fill a gap with a guess.
 */
class PlaceNamesTest {

    // A real Nominatim jsonv2 answer for a public landmark in Tambaram.
    private val nominatimBody = """
        {"place_id":249359470,"display_name":"Tambaram - Mudichur - Sriperumbudur Road, Kannadapalayam, Tambaram, Chengalpattu, Tamil Nadu, 600045, India",
         "address":{"road":"Tambaram - Mudichur - Sriperumbudur Road","suburb":"Kannadapalayam","city":"Tambaram","county":"Tambaram",
         "state_district":"Chengalpattu","state":"Tamil Nadu","postcode":"600045","country":"India"}}
    """.trimIndent()

    @Test
    fun `a nominatim answer becomes a two level name with the service's own full line`() {
        val place = PlaceNames.fromNominatim(nominatimBody)!!
        assertEquals("Kannadapalayam, Tambaram", place.short)
        assertEquals(
            "Tambaram - Mudichur - Sriperumbudur Road, Kannadapalayam, Tambaram, Chengalpattu, Tamil Nadu, 600045, India",
            place.full,
        )
        assertEquals(PlaceName.Source.OPENSTREETMAP, place.source)
    }

    @Test
    fun `without a neighbourhood the name falls back to the next coarser levels`() {
        val body = """{"display_name":"x","address":{"city":"Tambaram","county":"Tambaram","state":"Tamil Nadu"}}"""
        assertEquals("Tambaram, Tamil Nadu", PlaceNames.fromNominatim(body)!!.short)
    }

    @Test
    fun `an error body or an empty address yields no place rather than an invented one`() {
        assertNull(PlaceNames.fromNominatim("""{"error":"Unable to geocode"}"""))
        assertNull(PlaceNames.fromNominatim("""{"display_name":"somewhere","address":{}}"""))
        assertNull(PlaceNames.fromNominatim("not json"))
    }

    @Test
    fun `shorten drops blanks and duplicates and never exceeds two levels`() {
        assertEquals("Kundrathur, Tambaram", PlaceNames.shorten("Kundrathur", "Tambaram", "Chengalpattu"))
        assertEquals("Tambaram", PlaceNames.shorten(null, "Tambaram", "  ", "Tambaram"))
        assertEquals("Tambaram, Tamil Nadu", PlaceNames.shorten(null, "Tambaram", null, "Tamil Nadu"))
        assertNull(PlaceNames.shorten(null, "", "   "))
    }

    @Test
    fun `cache keys round to the precision actually sent to the lookup service`() {
        // 4 decimals is about 11 m; two fixes a few metres apart share an entry.
        assertEquals("12.9128_80.1402", PlaceNames.cacheKey(12.91284, 80.14018))
        assertEquals("12.9128_80.1402", PlaceNames.cacheKey(12.91281, 80.14024))
        assertEquals(12.9128, PlaceNames.rounded(12.91284), 0.0)
    }

    // The real line this phone's geocoder returned for a capture in Tambaram.
    private val realLine = "W47R+32Q, East Tambaram, Tambaram, Tamil Nadu 600073, India"

    @Test
    fun `the sub-locality is read from the service's own address line`() {
        assertEquals("East Tambaram", PlaceNames.neighbourhoodFromAddressLine(realLine, "Tambaram"))
        assertEquals(
            "East Tambaram, Tambaram",
            PlaceNames.shorten(PlaceNames.neighbourhoodFromAddressLine(realLine, "Tambaram"), "Tambaram", null, "Tamil Nadu"),
        )
    }

    @Test
    fun `a street or a door number is never promoted to a neighbourhood`() {
        assertNull(PlaceNames.neighbourhoodFromAddressLine("12, Main Rd, Chennai, Tamil Nadu 600001, India", "Chennai"))
        assertNull(PlaceNames.neighbourhoodFromAddressLine("Tambaram, Tamil Nadu, India", "Tambaram"))
        assertNull(PlaceNames.neighbourhoodFromAddressLine(null, "Tambaram"))
        assertNull(PlaceNames.neighbourhoodFromAddressLine(realLine, null))
    }

    // The three real shapes this phone's geocoder produced (cached on the device).
    @Test
    fun `town in subLocality with the finer area only in the line gains the finer area`() {
        assertEquals(
            "East Tambaram, Tambaram",
            PlaceNames.fromGeocoderFields("Tambaram", null, null, "Tamil Nadu", realLine),
        )
    }

    @Test
    fun `a plus code before the locality is never promoted`() {
        assertEquals(
            "Puduppair, Tamil Nadu",
            PlaceNames.fromGeocoderFields("Puduppair", null, null, "Tamil Nadu", "X2CR+5JX, Puduppair, Tamil Nadu 600133, India"),
        )
    }

    @Test
    fun `when the service gives both fields they are used as they are`() {
        assertEquals(
            "CHENNAI INSTITUTE OF TECHNOLOGY, Malayambakkam",
            PlaceNames.fromGeocoderFields(
                "CHENNAI INSTITUTE OF TECHNOLOGY", "Malayambakkam", null, "Tamil Nadu",
                "MAIN BLOCK, CHENNAI INSTITUTE OF TECHNOLOGY, Malayambakkam, Puduppair, Tamil Nadu 600133, India",
            ),
        )
    }

    @Test
    fun `the same name in both fields is one level, so the finer area is still found`() {
        // What the geocoder on the demo phone actually returned for this capture.
        assertEquals(
            "East Tambaram, Tambaram",
            PlaceNames.fromGeocoderFields("Tambaram", "Tambaram", null, "Tamil Nadu", realLine),
        )
    }
}
