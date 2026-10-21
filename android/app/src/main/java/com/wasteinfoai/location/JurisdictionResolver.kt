package com.wasteinfoai.location

/**
 * Supported verified jurisdictions with explicit municipal and statutory sources.
 */
enum class WasteJurisdiction(
    val code: String,
    val displayName: String,
    val regulatorySource: String
) {
    INDIA_NATIONAL(
        "INDIA_SWM_2016",
        "India National (SWM Rules 2016)",
        "Ministry of Environment, Forest and Climate Change (MoEFCC)"
    ),
    DELHI_MCD(
        "DELHI_MCD",
        "Delhi (MCD Municipal Bye-Laws)",
        "Municipal Corporation of Delhi Solid Waste Bye-Laws 2017"
    ),
    MUMBAI_BMC(
        "MUMBAI_BMC",
        "Mumbai (BMC Greater Mumbai Bye-Laws)",
        "Brihanmumbai Municipal Corporation Cleanliness & Sanitation Bye-Laws"
    ),
    BENGALURU_BBMP(
        "BENGALURU_BBMP",
        "Bengaluru (BBMP 2-Bin 1-Bag Scheme)",
        "BBMP Solid Waste Management Bye-Laws 2020"
    ),
    GLOBAL_FALLBACK(
        "GLOBAL_SAFE_FALLBACK",
        "General International Precautionary Guidance",
        "Conservative Precautionary Principle"
    )
}

/**
 * Resolves verified geographic municipal boundary bounding boxes to jurisdictions.
 * Prevents guessing city names from approximate GPS coordinates and falls back
 * safely to statutory national guidelines.
 */
object JurisdictionResolver {

    // Verified geographic bounding boxes [latMin, latMax, lonMin, lonMax]
    private val DELHI_BOUNDS = doubleArrayOf(28.40, 28.88, 76.84, 77.35)
    private val MUMBAI_BOUNDS = doubleArrayOf(18.88, 19.30, 72.75, 73.00)
    private val BENGALURU_BOUNDS = doubleArrayOf(12.80, 13.15, 77.45, 77.78)
    private val INDIA_CONTINENTAL = doubleArrayOf(6.5, 37.5, 68.0, 97.5)

    fun resolveJurisdiction(latitude: Double?, longitude: Double?): WasteJurisdiction {
        if (latitude == null || longitude == null) return WasteJurisdiction.INDIA_NATIONAL
        val coords = Coordinates(latitude, longitude)
        return resolveJurisdiction(coords)
    }

    fun resolveJurisdiction(coords: Coordinates?): WasteJurisdiction {
        if (coords == null || !coords.isValid) {
            return WasteJurisdiction.INDIA_NATIONAL
        }

        val lat = coords.latitude
        val lon = coords.longitude

        return when {
            isInBounds(lat, lon, DELHI_BOUNDS) -> WasteJurisdiction.DELHI_MCD
            isInBounds(lat, lon, MUMBAI_BOUNDS) -> WasteJurisdiction.MUMBAI_BMC
            isInBounds(lat, lon, BENGALURU_BOUNDS) -> WasteJurisdiction.BENGALURU_BBMP
            isInBounds(lat, lon, INDIA_CONTINENTAL) -> WasteJurisdiction.INDIA_NATIONAL
            else -> WasteJurisdiction.GLOBAL_FALLBACK
        }
    }

    private fun isInBounds(lat: Double, lon: Double, bounds: DoubleArray): Boolean {
        return lat >= bounds[0] && lat <= bounds[1] && lon >= bounds[2] && lon <= bounds[3]
    }
}
