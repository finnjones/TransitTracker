package app.transittracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformsTest {
    private fun stop(name: String, code: String? = null) = Stop("1", name, position = LatLng(-33.88, 151.2), platformCode = code)

    @Test
    fun trainsMetroAndLightRailUseTheirCode() {
        assertEquals("16", Platforms.of(stop("Central Station Platform 16", "16")))
        assertEquals("1", Platforms.of(stop("Barangaroo Station, Platform 1", "1")))
        assertEquals("2", Platforms.of(stop("Paddy's Markets Light Rail", "2")))
    }

    @Test
    fun busStandsComeFromTheName() {
        // TfNSW bus stops have no platform_code: the stand is only in the name.
        assertEquals("Stand A", Platforms.of(stop("Wynyard Station, Carrington St, Stand A")))
        assertEquals("Stand J", Platforms.of(stop("Central Station, Railway Square, Stand J")))
        assertNull(Platforms.of(stop("Glenayr Ave at Hall St")))
    }

    @Test
    fun streetNamesAreNotPlatforms() {
        assertNull(Platforms.of(stop("Waverton Station, Bay Rd")))
        assertNull(Platforms.of(stop("Fairfield Heights Park, Station St")))
        assertNull(Platforms.of(stop("Albion St opp St Catherine's School, Gate 1")))
    }

    @Test
    fun ferriesKeepTheWharfAndSide() {
        assertEquals("Wharf 3, Side A", Platforms.of(stop("Circular Quay, Wharf 3, Side A", "A")))
        assertEquals("Wharf 1", Platforms.of(stop("Manly, Wharf 1")))
        assertEquals("Side B", Platforms.of(stop("Balmain East Wharf, Side B", "B")))
        assertNull(Platforms.of(stop("Abbotsford Wharf")))
    }

    @Test
    fun nameAloneWhenThereIsNoCode() {
        assertEquals("16", Platforms.fromName("Central Station, Platform 16"))
        assertEquals("16", Platforms.of(stop("Central Station Platform 16")))
        assertNull(Platforms.of(null))
    }
}
