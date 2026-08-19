package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.haversineKm
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

data class PublicPlace(
    val id: String,
    val label: String,
    val point: GeoPoint,
)

class PublicPlaceIndex private constructor(
    places: List<PublicPlace>,
) {
    private val placesByCell: Map<WorldCell, List<PublicPlace>>
    private val placesById: Map<String, PublicPlace>

    init {
        val labeledPlaces = disambiguateDuplicateLabels(places)
        placesByCell = labeledPlaces.groupBy { cellFor(it.point) }
        placesById = labeledPlaces.associateBy(PublicPlace::id)
    }

    fun nearest(point: GeoPoint, maxDistanceKm: Double): PublicPlace? =
        nearestMatching(point, maxDistanceKm, allowedIds = null)

    fun nearest(point: GeoPoint, maxDistanceKm: Double, allowedIds: Set<String>): PublicPlace? =
        if (allowedIds.isEmpty()) null else nearestMatching(point, maxDistanceKm, allowedIds)

    private fun nearestMatching(
        point: GeoPoint,
        maxDistanceKm: Double,
        allowedIds: Set<String>?,
    ): PublicPlace? {
        require(maxDistanceKm.isFinite() && maxDistanceKm >= 0.0) {
            "Maximum distance must be finite and non-negative"
        }
        var nearest: PublicPlace? = null
        var nearestDistanceKm = maxDistanceKm
        nearbyCells(point, maxDistanceKm).forEach { cell ->
            placesByCell[cell].orEmpty().forEach placeLoop@{ place ->
                if (allowedIds != null && place.id !in allowedIds) return@placeLoop
                val distanceKm = haversineKm(point, place.point)
                val currentNearest = nearest
                if (
                    distanceKm <= maxDistanceKm &&
                    (currentNearest == null ||
                        distanceKm < nearestDistanceKm ||
                        (distanceKm == nearestDistanceKm && place.id < currentNearest.id))
                ) {
                    nearest = place
                    nearestDistanceKm = distanceKm
                }
            }
        }
        return nearest
    }

    fun distinctNearest(points: Sequence<GeoPoint>, maxDistanceKm: Double): List<PublicPlace> =
        points
            .mapNotNull { nearest(it, maxDistanceKm) }
            .distinctBy(PublicPlace::id)
            .sortedBy(PublicPlace::label)
            .toList()

    fun find(ids: Set<String>): List<PublicPlace> =
        ids.mapNotNull(placesById::get).sortedBy(PublicPlace::label)

    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<PublicPlace> {
        require(limit > 0) { "Search limit must be positive" }
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return placesById.values.asSequence()
            .filter { it.label.contains(term, ignoreCase = true) }
            .sortedWith(
                compareBy<PublicPlace> { !it.label.startsWith(term, ignoreCase = true) }
                    .thenBy(PublicPlace::label)
                    .thenBy(PublicPlace::id),
            )
            .take(limit)
            .toList()
    }

    private fun nearbyCells(point: GeoPoint, maxDistanceKm: Double): Sequence<WorldCell> {
        val center = cellFor(point)
        val latitudeRadius = ceil(maxDistanceKm / KM_PER_LATITUDE_DEGREE).toInt() + 1
        val longitudeKmPerDegree = KM_PER_LONGITUDE_DEGREE *
            abs(cos(point.latitude * PI / 180.0))
        val longitudeRadius = if (longitudeKmPerDegree < MIN_KM_PER_LONGITUDE_DEGREE) {
            180
        } else {
            (ceil(maxDistanceKm / longitudeKmPerDegree).toInt() + 1).coerceAtMost(180)
        }
        return sequence {
            for (latitude in (center.latitude - latitudeRadius)..(center.latitude + latitudeRadius)) {
                if (latitude !in -90..89) continue
                for (longitudeOffset in -longitudeRadius..longitudeRadius) {
                    yield(WorldCell(latitude, wrapLongitudeCell(center.longitude + longitudeOffset)))
                }
            }
        }
    }

    companion object {
        private const val KM_PER_LATITUDE_DEGREE = 110.574
        private const val KM_PER_LONGITUDE_DEGREE = 111.320
        private const val MIN_KM_PER_LONGITUDE_DEGREE = 0.1
        private const val DEFAULT_SEARCH_LIMIT = 100

        private fun disambiguateDuplicateLabels(places: List<PublicPlace>): List<PublicPlace> {
            val labelCounts = places.groupingBy(PublicPlace::label).eachCount()
            return places.map { place ->
                if (labelCounts[place.label] == 1) {
                    place
                } else {
                    place.copy(
                        label = String.format(
                            Locale.ROOT,
                            "%s [%.2f, %.2f]",
                            place.label,
                            place.point.latitude,
                            place.point.longitude,
                        ),
                    )
                }
            }
        }

        fun read(input: InputStream): PublicPlaceIndex {
            val places = input.bufferedReader().useLines { lines ->
                lines.filterNot { it.isBlank() || it.startsWith('#') }
                    .map { line ->
                        val columns = line.split(',')
                        require(columns.size == 3 || columns.size == 5) { "Invalid public-place index row" }
                        val id = columns[0]
                        val label = if (columns.size == 5) {
                            "${URLDecoder.decode(columns[4], StandardCharsets.UTF_8.name())} (${columns[3]})"
                        } else {
                            id
                        }
                        PublicPlace(
                            id = id,
                            label = label,
                            point = GeoPoint(
                                instant = Instant.EPOCH,
                                latitude = columns[1].toDouble(),
                                longitude = columns[2].toDouble(),
                            ),
                        )
                    }
                    .toList()
            }
            require(places.isNotEmpty()) { "Public-place index is empty" }
            return PublicPlaceIndex(places)
        }

        internal fun fromCoordinates(coordinates: List<Pair<Double, Double>>) = PublicPlaceIndex(
            coordinates.mapIndexed { index, (latitude, longitude) ->
                PublicPlace(
                    id = index.toString(),
                    label = index.toString(),
                    point = GeoPoint(Instant.EPOCH, latitude, longitude),
                )
            },
        )
    }
}

private data class WorldCell(
    val latitude: Int,
    val longitude: Int,
)

private fun cellFor(point: GeoPoint) = WorldCell(
    latitude = floor(point.latitude.coerceIn(-89.999999, 89.999999)).toInt(),
    longitude = floor(point.longitude.coerceIn(-179.999999, 179.999999)).toInt(),
)

private fun wrapLongitudeCell(longitude: Int): Int =
    Math.floorMod(longitude + 180, 360) - 180
