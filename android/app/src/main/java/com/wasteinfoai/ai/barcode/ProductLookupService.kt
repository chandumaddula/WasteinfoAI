package com.wasteinfoai.ai.barcode

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured packaging information extracted from barcode product databases.
 */
data class ProductPackagingInfo(
    val barcode: String,
    val productName: String,
    val brands: String?,
    val packagingDescription: String?,
    val coarseCategory: String, // Maps to one of 17 canonical classes (plastic, metal, cardboard, glass, etc.)
    val specificItem: String?,  // e.g., "PET Beverage Bottle", "Aluminum Beverage Can"
    val materialCode: String?,  // e.g., "PET 01", "ALU 41", "HDPE 02"
    val components: List<PackagingComponent> = emptyList(),
    val dataSource: String      // e.g., "Local Packaging DB", "Open Food Facts"
)

data class PackagingComponent(
    val part: String,           // e.g., "Bottle Body", "Cap", "Label"
    val material: String,       // e.g., "PET", "PP", "Plastic Film"
    val isSeparable: Boolean = true,
    val targetBinColor: String = "BLUE"
)

/**
 * Service to resolve barcodes to product packaging materials.
 * Operates offline-first using an embedded local database, and queries Open Food Facts
 * only when network connectivity permits with strict timeouts.
 */
class ProductLookupService {

    companion object {
        private const val TAG = "ProductLookupService"
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 2500
        private const val MISS_TTL_MS = 10 * 60 * 1000L
        private val BARCODE_REGEX = Regex("""^\d{8,14}$""")

        // Embedded offline lookup database for common consumer products (India & International)
        private val LOCAL_PACKAGING_CATALOG = mapOf(
            // Beverages - PET Bottles
            "8901764012235" to ProductPackagingInfo(
                barcode = "8901764012235",
                productName = "Bisleri Packaged Drinking Water 1L",
                brands = "Bisleri",
                packagingDescription = "Clear transparent PET bottle with plastic screw cap and label",
                coarseCategory = "plastic",
                specificItem = "PET Beverage Bottle",
                materialCode = "PET 01",
                components = listOf(
                    PackagingComponent("Bottle Body", "PET (Polyethylene Terephthalate)", false, "BLUE"),
                    PackagingComponent("Cap", "PP (Polypropylene)", true, "BLUE"),
                    PackagingComponent("Label", "BOPP Film", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "8901764012242" to ProductPackagingInfo(
                barcode = "8901764012242",
                productName = "Bisleri Packaged Drinking Water 500ml",
                brands = "Bisleri",
                packagingDescription = "Clear transparent PET bottle",
                coarseCategory = "plastic",
                specificItem = "PET Beverage Bottle",
                materialCode = "PET 01",
                components = listOf(
                    PackagingComponent("Bottle Body", "PET", false, "BLUE"),
                    PackagingComponent("Cap", "PP", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "5449000000996" to ProductPackagingInfo(
                barcode = "5449000000996",
                productName = "Coca-Cola Original 330ml Can",
                brands = "Coca-Cola",
                packagingDescription = "Aluminum beverage can with stay-on tab",
                coarseCategory = "metal",
                specificItem = "Aluminum Beverage Can",
                materialCode = "ALU 41",
                components = listOf(
                    PackagingComponent("Can Body & Lid", "Aluminum", false, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "8901764032127" to ProductPackagingInfo(
                barcode = "8901764032127",
                productName = "Frooti Mango Drink 160ml Tetra Pak",
                brands = "Frooti",
                packagingDescription = "Aseptic multilayer liquid carton (paper, polyethylene, aluminum foil)",
                coarseCategory = "cardboard",
                specificItem = "Multilayer Liquid Packaging Board (Tetra Pak)",
                materialCode = "C/PAP 84",
                components = listOf(
                    PackagingComponent("Carton Body", "Aseptic Paperboard/Polyethylene/Foil", false, "BLUE"),
                    PackagingComponent("Straw Wrapper", "Plastic Film", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "8901058852448" to ProductPackagingInfo(
                barcode = "8901058852448",
                productName = "Maggi 2-Minute Noodles Masala 70g",
                brands = "Maggi / Nestlé",
                packagingDescription = "Flexible plastic laminated pouch",
                coarseCategory = "plastic",
                specificItem = "Multi-Layer Plastic Laminate Pouch",
                materialCode = "OTHER 07",
                components = listOf(
                    PackagingComponent("Outer Pouch", "Metallized Plastic Laminate", false, "BLUE"),
                    PackagingComponent("Flavor Sachet", "Foil / Plastic Sachet", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "9002490100070" to ProductPackagingInfo(
                barcode = "9002490100070",
                productName = "Red Bull Energy Drink 250ml",
                brands = "Red Bull",
                packagingDescription = "100% recyclable aluminum can",
                coarseCategory = "metal",
                specificItem = "Aluminum Beverage Can",
                materialCode = "ALU 41",
                components = listOf(
                    PackagingComponent("Can Body & Tab", "Aluminum", false, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "8901030383748" to ProductPackagingInfo(
                barcode = "8901030383748",
                productName = "Tata Salt Iodized Salt 1kg",
                brands = "Tata Salt",
                packagingDescription = "Low Density Polyethylene (LDPE) food-grade pouch",
                coarseCategory = "plastic",
                specificItem = "LDPE Plastic Bag / Pouch",
                materialCode = "LDPE 04",
                components = listOf(
                    PackagingComponent("Pouch", "LDPE (Low Density Polyethylene)", false, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "8901719101014" to ProductPackagingInfo(
                barcode = "8901719101014",
                productName = "Parle-G Gold Biscuits 1kg",
                brands = "Parle-G",
                packagingDescription = "Printed BOPP plastic wrapper and inner corrugated tray",
                coarseCategory = "plastic",
                specificItem = "BOPP Plastic Wrapper",
                materialCode = "PP 05",
                components = listOf(
                    PackagingComponent("Outer Wrapper", "BOPP Plastic Film", false, "BLUE"),
                    PackagingComponent("Inner Tray", "Paperboard", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            ),
            "5000394002951" to ProductPackagingInfo(
                barcode = "5000394002951",
                productName = "Duracell Ultra AA Alkaline Batteries",
                brands = "Duracell",
                packagingDescription = "Alkaline manganese dioxide battery with blister card packaging",
                coarseCategory = "Battery",
                specificItem = "Alkaline AA Battery Cell",
                materialCode = "BATTERY_ALKALINE",
                components = listOf(
                    PackagingComponent("Battery Cell", "Alkaline / Zinc / Manganese Dioxide", false, "RED"),
                    PackagingComponent("Blister Card", "Recycled Cardboard & PET Blister", true, "BLUE")
                ),
                dataSource = "Local Packaging DB"
            )
        )
    }

    private val cache = ConcurrentHashMap<String, ProductPackagingInfo>(LOCAL_PACKAGING_CATALOG)
    /** Barcodes the online DB definitively does not know / cannot classify (avoids repeat slow lookups). */
    private val misses = ConcurrentHashMap<String, Long>()

    /**
     * Looks up packaging details for a decoded barcode.
     * Checks local cache first; if missing and online, queries Open Food Facts API v2.
     */
    suspend fun lookup(barcode: String): ProductPackagingInfo? = withContext(Dispatchers.IO) {
        val cleanBarcode = barcode.trim()
        // Only plain GTIN digits are ever placed in a request URL.
        if (!BARCODE_REGEX.matches(cleanBarcode)) return@withContext null

        // 1. Instant offline cache lookup
        cache[cleanBarcode]?.let { return@withContext it }

        val missedAt = misses[cleanBarcode]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_TTL_MS) return@withContext null

        // 2. Query Open Food Facts v2 if network available
        return@withContext queryOpenFoodFacts(cleanBarcode)?.also { info ->
            cache[cleanBarcode] = info
        }
    }

    private fun queryOpenFoodFacts(barcode: String): ProductPackagingInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,packaging,packaging_text,packaging_tags")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "wasteinfoAI-Android/1.0.0 (contact@wasteinfoai.internal)")
            }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                misses[barcode] = System.currentTimeMillis()
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return null // transient: do not negative-cache
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseOpenFoodFactsJson(barcode, responseText)
            if (parsed == null) misses[barcode] = System.currentTimeMillis() // definitive: unknown / unclassifiable
            parsed
        } catch (e: Exception) {
            Log.d(TAG, "Open Food Facts lookup failed for $barcode: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private data class PackagingMaterial(val coarse: String, val item: String, val code: String)

    private val nonAlnum = Regex("[^a-z0-9]+")
    private val metalWords = setOf("aluminium", "aluminum", "steel", "tin", "tinplate", "can", "cans", "metal")
    private val glassWords = setOf("glass")
    private val plasticWords = setOf(
        "plastic", "plastics", "pet", "pete", "hdpe", "ldpe", "pp", "pvc", "ps",
        "polypropylene", "polyethylene", "polystyrene", "pouch", "sachet", "film", "wrapper", "bag"
    )
    private val cardboardWords = setOf("cardboard", "carton", "tetra", "tetrapak", "paperboard", "box", "paper", "brik")

    private fun parseOpenFoodFactsJson(barcode: String, json: String): ProductPackagingInfo? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("status", 0) != 1) return null
            val product = root.optJSONObject("product") ?: return null

            val name = product.optString("product_name").takeIf { it.isNotBlank() } ?: "Packaged Consumer Product"
            val brands = product.optString("brands").takeIf { it.isNotBlank() }
            val packaging = product.optString("packaging").takeIf { it.isNotBlank() }
                ?: product.optString("packaging_text").takeIf { it.isNotBlank() }

            // Classify from PACKAGING fields only. The product name used to be included, so "Candy" matched
            // "can" (metal), "Protein" matched "tin" (metal), "Apple" matched "pp" (plastic).
            val tags = product.optJSONArray("packaging_tags")
            val tagText = if (tags != null) List(tags.length()) { tags.optString(it) }.joinToString(" ") else ""
            val packagingText = listOf(
                product.optString("packaging"), product.optString("packaging_text"), tagText
            ).joinToString(" ")

            // No usable packaging evidence -> return null so the vision model decides.
            // (Previously unknown packaging became "trash" at 95% confidence and overrode the image.)
            val material = classifyPackaging(packagingText) ?: return null

            ProductPackagingInfo(
                barcode = barcode,
                productName = name,
                brands = brands,
                packagingDescription = packaging,
                coarseCategory = material.coarse,
                specificItem = material.item,
                materialCode = material.code,
                components = emptyList(),
                dataSource = "Open Food Facts"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Open Food Facts response", e)
            null
        }
    }

    /** Whole-word token voting. Ties between materials are treated as ambiguous (null). */
    private fun classifyPackaging(text: String): PackagingMaterial? {
        val tokens = text.lowercase().split(nonAlnum).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val set = tokens.toSet()

        val counts = linkedMapOf(
            "cardboard" to tokens.count { it in cardboardWords },
            "glass" to tokens.count { it in glassWords },
            "plastic" to tokens.count { it in plasticWords },
            "metal" to tokens.count { it in metalWords }
        )
        val max = counts.values.maxOrNull() ?: 0
        if (max == 0) return null
        val winners = counts.filterValues { it == max }.keys
        if (winners.size != 1) return null

        return when (winners.first()) {
            "metal" ->
                if (set.any { it == "steel" || it == "tin" || it == "tinplate" })
                    PackagingMaterial("metal", "Steel / Tin Food Can", "FE 40")
                else PackagingMaterial("metal", "Aluminum Beverage Can", "ALU 41")
            "glass" -> PackagingMaterial("glass", "Glass Container", "GL 70")
            "cardboard" ->
                if ("tetra" in set || "tetrapak" in set || "brik" in set)
                    PackagingMaterial("cardboard", "Multilayer Liquid Packaging Board (Tetra Pak)", "C/PAP 84")
                else PackagingMaterial("cardboard", "Cardboard Box / Liquid Carton", "PAP 20")
            else -> when {
                "pet" in set || "pete" in set -> PackagingMaterial("plastic", "PET Beverage Bottle", "PET 01")
                "hdpe" in set -> PackagingMaterial("plastic", "HDPE Rigid Container", "HDPE 02")
                "pvc" in set -> PackagingMaterial("plastic", "PVC Plastic Packaging", "PVC 03")
                "ldpe" in set -> PackagingMaterial("plastic", "LDPE Plastic Bag / Pouch", "LDPE 04")
                "pp" in set || "polypropylene" in set -> PackagingMaterial("plastic", "PP Plastic Container", "PP 05")
                "ps" in set || "polystyrene" in set -> PackagingMaterial("plastic", "Polystyrene Packaging", "PS 06")
                else -> PackagingMaterial("plastic", "Plastic Packaging", "PLASTIC_UNKNOWN")
            }
        }
    }
}
