package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class TimelineAnonymizerTest {
    private val cityIndex = PublicPlaceIndex.fromCoordinates(
        listOf(
            1.3521 to 103.8198,
            -33.8688 to 151.2093,
            64.1466 to -21.9426,
            35.7767 to 140.3189,
        ),
    )

    @Test
    fun anonymizesOnlySelectedCities() {
        val points = listOf(
            point("2025-01-01T00:00:00Z", 1.2837, 103.8254),
            point("2025-01-01T01:00:00Z", -33.8100, 151.1500),
            point("2025-01-01T02:00:00Z", 64.1000, -21.9000),
        )
        val result = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0"))

        assertEquals(1.3521, result.points[0].latitude, 0.00001)
        assertEquals(103.8198, result.points[0].longitude, 0.00001)
        assertEquals(points[1], result.points[1])
        assertEquals(points[2], result.points[2])
        assertEquals(1, result.changedCount)
    }

    @Test
    fun leavesFlyingSegmentsUntouchedInsideProtectedCities() {
        val points = listOf(
            point("2025-03-05T01:30:21Z", 1.3558644, 103.9844364, isFlying = true),
            point("2025-03-05T08:14:29Z", 35.7589093, 140.3866294, isFlying = true),
        )

        val result = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0", "3"))

        assertEquals(points, result.points)
        assertEquals(0, result.changedCount)
    }

    @Test
    fun usesCityCenterForGroundMovementInProtectedArea() {
        val points = listOf(
            point("2025-01-01T00:00:00Z", 1.3558, 103.9844),
            point("2025-01-01T00:10:00Z", 1.3560, 103.9850),
        )

        val result = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0"))

        assertEquals(listOf(1.3521, 1.3521), result.points.map { it.latitude })
        assertEquals(listOf(103.8198, 103.8198), result.points.map { it.longitude })
    }

    @Test
    fun leavesEveryPointUntouchedWhenNoPrivateCitiesAreSelected() {
        val points = listOf(
            point("2025-01-01T00:00:00Z", 1.2837, 103.8254),
            point("2025-01-01T10:00:00Z", 35.7589, 140.3866),
        )

        val result = TimelineAnonymizer.anonymize(points, cityIndex, emptySet())

        assertEquals(points, result.points)
        assertSame(points, result.points)
        assertEquals(0, result.changedCount)
    }

    @Test
    fun leavesRemotePointsUntouched() {
        val original = point("2025-01-01T00:00:00Z", 10.1677, 111.6336)
        val points = listOf(original)

        val result = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0"))

        assertEquals(original, result.points.single())
        assertSame(points, result.points)
    }

    @Test
    fun protectsSelectedRadiusEvenWhenAnotherCityIsCloser() {
        val overlappingCities = PublicPlaceIndex.fromCoordinates(
            listOf(0.0 to 0.0, 0.0 to 0.1),
        )
        val original = point("2025-01-01T00:00:00Z", 0.0, 0.09)

        val result = TimelineAnonymizer.anonymize(
            listOf(original),
            overlappingCities,
            setOf("0"),
        )

        assertEquals(0.0, result.points.single().longitude, 0.00001)
    }

    @Test
    fun respectsSelectedPrivacyRadius() {
        val original = point("2025-01-01T00:00:00Z", 1.6521, 103.8198)

        val narrow = TimelineAnonymizer.anonymize(
            listOf(original),
            cityIndex,
            setOf("0"),
            privateAreaRadiusKm = 25.0,
        )
        val wide = TimelineAnonymizer.anonymize(
            listOf(original),
            cityIndex,
            setOf("0"),
            privateAreaRadiusKm = 50.0,
        )

        assertEquals(original, narrow.points.single())
        assertEquals(1.3521, wide.points.single().latitude, 0.00001)
        assertEquals(103.8198, wide.points.single().longitude, 0.00001)
    }

    @Test
    fun readsNamedPlacesAndMatchesAcrossDateLine() {
        val input = """
            # id,latitude,longitude,country,encoded_name
            42,10.000000,-179.900000,FJ,Test%20City
        """.trimIndent().byteInputStream()
        val index = PublicPlaceIndex.read(input)

        val nearest = index.nearest(point("2025-01-01T00:00:00Z", 10.0, 179.9), 30.0)

        assertEquals("42", nearest?.id)
        assertEquals("Test City (FJ)", nearest?.label)
        assertEquals(-179.9, nearest?.point?.longitude ?: Double.NaN, 0.00001)
    }

    @Test
    fun isDeterministic() {
        val points = listOf(
            point("2025-01-01T00:00:00Z", 1.2837, 103.8254),
            point("2025-01-01T10:00:00Z", 35.7589, 140.3866),
        )

        val first = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0", "3"))
        val second = TimelineAnonymizer.anonymize(points, cityIndex, setOf("0", "3"))

        assertEquals(first, second)
    }

    @Test
    fun nearestCityUsesStableIdToBreakDistanceTies() {
        val index = PublicPlaceIndex.read(
            """
            20,0.000000,-0.100000,ZZ,West
            10,0.000000,0.100000,ZZ,East
            """.trimIndent().byteInputStream(),
        )

        val nearest = index.nearest(point("2025-01-01T00:00:00Z", 0.0, 0.0), 20.0)

        assertEquals("10", nearest?.id)
    }

    @Test
    fun duplicateCityLabelsIncludeCoordinates() {
        val index = PublicPlaceIndex.read(
            """
            10,1.000000,2.000000,ZZ,Springfield
            20,3.000000,4.000000,ZZ,Springfield
            30,5.000000,6.000000,ZZ,Unique
            """.trimIndent().byteInputStream(),
        )

        assertEquals("Springfield (ZZ) [1.00, 2.00]", index.find(setOf("10")).single().label)
        assertEquals("Springfield (ZZ) [3.00, 4.00]", index.find(setOf("20")).single().label)
        assertEquals("Unique (ZZ)", index.find(setOf("30")).single().label)
    }

    @Test
    fun worldwideSearchPrefersPrefixMatchesAndIsLimited() {
        val index = PublicPlaceIndex.read(
            """
            10,1.000000,2.000000,SG,Singapore
            20,3.000000,4.000000,US,Singapore%20Park
            30,5.000000,6.000000,ZZ,New%20Singapore
            """.trimIndent().byteInputStream(),
        )

        assertEquals(
            listOf("Singapore (SG)", "Singapore Park (US)"),
            index.search("  singapore ", limit = 2).map(PublicPlace::label),
        )
        assertEquals(emptyList<PublicPlace>(), index.search(" "))
    }

    @Test
    fun rejectsInvalidPrivacyRadius() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineAnonymizer.anonymize(emptyList(), cityIndex, setOf("0"), Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineAnonymizer.anonymize(emptyList(), cityIndex, setOf("0"), -1.0)
        }
    }

    private fun point(
        instant: String,
        latitude: Double,
        longitude: Double,
        isFlying: Boolean = false,
    ) = GeoPoint(Instant.parse(instant), latitude, longitude, isFlying)
}
