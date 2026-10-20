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

    /**
     * Looks up packaging details for a decoded barcode.
     * Checks local cache first; if missing and online, queries Open Food Facts API v2.
     */
    suspend fun lookup(barcode: String): ProductPackagingInfo? = withContext(Dispatchers.IO) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isBlank()) return@withContext null

        // 1. Instant offline cache lookup
        cache[cleanBarcode]?.let { return@withContext it }

        // 2. Query Open Food Facts v2 if network available
        return@withContext queryOpenFoodFacts(cleanBarcode)?.also { info ->
            cache[cleanBarcode] = info
        }
    }

    private fun queryOpenFoodFacts(barcode: String): ProductPackagingInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,packaging,packaging_text,ecoscore_data")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "wasteinfoAI-Android/1.0.0 (contact@wasteinfoai.internal)")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            parseOpenFoodFactsJson(barcode, responseText)
        } catch (e: Exception) {
            Log.d(TAG, "Open Food Facts lookup failed for $barcode: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseOpenFoodFactsJson(barcode: String, json: String): ProductPackagingInfo? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("status", 0) != 1) return null

            val product = root.optJSONObject("product") ?: return null
            val name = product.optString("product_name").takeIf { it.isNotBlank() } ?: "Packaged Consumer Product"
            val brands = product.optString("brands").takeIf { it.isNotBlank() }
            val packaging = product.optString("packaging").takeIf { it.isNotBlank() }
                ?: product.optString("packaging_text").takeIf { it.isNotBlank() }

            // Deduce coarse category and material code from packaging text
            val lower = (name + " " + (packaging ?: "")).lowercase()
            val (coarse, item, code) = when {
                lower.contains("aluminum") || lower.contains("can") || lower.contains("tin") || lower.contains("metal") ->
                    Triple("metal", "Aluminum Beverage Can", "ALU 41")
                lower.contains("glass") || lower.contains("bottle") && lower.contains("glass") ->
                    Triple("glass", "Glass Container", "GL 70")
                lower.contains("carton") || lower.contains("box") || lower.contains("cardboard") || lower.contains("tetra") ->
                    Triple("cardboard", "Cardboard Box / Liquid Carton", "PAP 20")
                lower.contains("pet") || lower.contains("polyethylene terephthalate") ->
                    Triple("plastic", "PET Beverage Bottle", "PET 01")
                lower.contains("hdpe") ->
                    Triple("plastic", "HDPE Rigid Container", "HDPE 02")
                lower.contains("pp") || lower.contains("polypropylene") ->
                    Triple("plastic", "PP Plastic Container", "PP 05")
                lower.contains("plastic") || lower.contains("pouch") || lower.contains("bottle") || lower.contains("sachet") ->
                    Triple("plastic", "Plastic Packaging", "PLASTIC_UNKNOWN")
                else ->
                    Triple("trash", "Packaged Waste Item", "OTHER")
            }

            ProductPackagingInfo(
                barcode = barcode,
                productName = name,
                brands = brands,
                packagingDescription = packaging,
                coarseCategory = coarse,
                specificItem = item,
                materialCode = code,
                components = emptyList(),
                dataSource = "Open Food Facts"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Open Food Facts response", e)
            null
        }
    }
}
