package dev.mahlernim.timelinevisualizer.data

import dev.mahlernim.timelinevisualizer.model.GeoPoint

data class TimelineAnonymizationResult(
    val points: List<GeoPoint>,
    val changedCount: Int,
)

object TimelineAnonymizer {
    fun anonymize(
        points: List<GeoPoint>,
        cityIndex: PublicPlaceIndex,
        privateCityIds: Set<String>,
        privateAreaRadiusKm: Double = DEFAULT_PRIVATE_AREA_RADIUS_KM,
    ): TimelineAnonymizationResult {
        require(privateAreaRadiusKm.isFinite() && privateAreaRadiusKm >= 0.0) {
            "Private-area radius must be a finite non-negative distance"
        }
        if (points.isEmpty() || privateCityIds.isEmpty()) {
            return TimelineAnonymizationResult(points, changedCount = 0)
        }

        var changedCount = 0
        var anonymized: MutableList<GeoPoint>? = null
        points.forEachIndexed { index, point ->
            if (point.isFlying) return@forEachIndexed
            val city = cityIndex.nearest(point, privateAreaRadiusKm, privateCityIds)
            val replacement = city?.point ?: return@forEachIndexed
            if (
                replacement.latitude.toBits() == point.latitude.toBits() &&
                replacement.longitude.toBits() == point.longitude.toBits()
            ) {
                return@forEachIndexed
            }
            changedCount += 1
            val output = anonymized ?: points.toMutableList().also { anonymized = it }
            output[index] = point.copy(
                latitude = replacement.latitude,
                longitude = replacement.longitude,
            )
        }
        return TimelineAnonymizationResult(anonymized ?: points, changedCount)
    }

    const val DEFAULT_PRIVATE_AREA_RADIUS_KM = 50.0
    const val MAX_PRIVATE_AREA_RADIUS_KM = 100.0
}
