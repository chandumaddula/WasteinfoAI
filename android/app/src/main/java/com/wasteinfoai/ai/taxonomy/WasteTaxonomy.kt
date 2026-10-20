package com.wasteinfoai.ai.taxonomy

/**
 * Origin of waste identification evidence.
 */
enum class IdentificationSource {
    VISION_CLASSIFIER,
    OBJECT_CROP_CLASSIFIER,
    BARCODE_LOOKUP,
    OCR_OR_SYMBOL,
    USER_CONFIRMED,
    CLOUD_VISION,
    UNKNOWN
}

data class TaxonomyComponent(
    val partName: String,
    val material: String,
    val isSeparable: Boolean,
    val targetBinColor: String
)

/**
 * Separates the coarse 17-class model prediction from specific physical item identity
 * and material composition according to verified evidence.
 */
data class HierarchicalIdentification(
    val coarseCategory: String,
    val specificItem: String?,
    val materialCode: String?,
    val materialDescription: String?,
    val identificationSource: IdentificationSource,
    val confidence: Float,
    val evidenceSummary: String,
    val needsUserConfirmation: Boolean,
    val components: List<TaxonomyComponent> = emptyList()
) {
    companion object {
        fun fromVisionClassification(
            category: String,
            confidence: Float,
            isCropped: Boolean
        ): HierarchicalIdentification {
            val isUncertain = confidence < 0.60f
            val source = if (isCropped) IdentificationSource.OBJECT_CROP_CLASSIFIER else IdentificationSource.VISION_CLASSIFIER
            return HierarchicalIdentification(
                coarseCategory = category,
                specificItem = null, // Coarse vision cannot reliably determine exact plastic resin or battery chemistry
                materialCode = null,
                materialDescription = "Generic material inferred from visual inspection",
                identificationSource = source,
                confidence = confidence,
                evidenceSummary = "Classified as $category using on-device ReXNet-150 ($source, conf: ${(confidence * 100).toInt()}%)",
                needsUserConfirmation = isUncertain
            )
        }
    }
}

data class SpecificWasteCandidate(
    val id: String,
    val displayName: String,
    val coarseCategory: String,
    val materialCode: String?,
    val targetBinColor: String,
    val description: String
)

/**
 * Canonical taxonomy mapping for the 17 verified classes to real-world sub-items and material codes.
 */
object WasteTaxonomy {

    private val CANDIDATES_BY_CATEGORY: Map<String, List<SpecificWasteCandidate>> = mapOf(
        "plastic" to listOf(
            SpecificWasteCandidate("item-pet-bottle", "PET Beverage Bottle (RIC 1)", "plastic", "PET 01", "BLUE", "Clear/tinted water or soda bottle. Highly recyclable."),
            SpecificWasteCandidate("item-hdpe-jug", "HDPE Rigid Bottle / Jug (RIC 2)", "plastic", "HDPE 02", "BLUE", "Milk jugs, shampoo bottles, detergent containers."),
            SpecificWasteCandidate("item-ldpe-bag", "LDPE Film / Carry Bag (RIC 4)", "plastic", "LDPE 04", "BLUE", "Grocery bags, cling wrap, squeezable containers."),
            SpecificWasteCandidate("item-pp-container", "PP Food Container / Straw (RIC 5)", "plastic", "PP 05", "BLUE", "Yogurt cups, food takeaway tubs, medicine bottles."),
            SpecificWasteCandidate("item-ps-foam", "EPS Styrofoam / Packaging (RIC 6)", "plastic", "PS 06", "RED", "Thermocol packing inserts, foam cups. Non-curbside."),
            SpecificWasteCandidate("item-multi-layer", "Multi-Layer Plastic Pouch (RIC 7)", "plastic", "OTHER 07", "BLUE", "Metallized snack/chip wrappers, condiment pouches."),
            SpecificWasteCandidate("item-generic-plastic", "Other / Unknown Plastic", "plastic", null, "BLUE", "Mixed or unverified plastic packaging.")
        ),
        "metal" to listOf(
            SpecificWasteCandidate("item-alu-can", "Aluminum Beverage Can", "metal", "ALU 41", "BLUE", "Beer and soda cans. Infinitely recyclable."),
            SpecificWasteCandidate("item-steel-can", "Steel / Tin Food Can", "metal", "FE 40", "BLUE", "Canned vegetables, soup tins, biscuit tins."),
            SpecificWasteCandidate("item-aerosol-can", "Aerosol Spray Container", "metal", "FE 40", "RED", "Deodorant or paint sprays. Pressurized safety hazard if full."),
            SpecificWasteCandidate("item-foil", "Aluminum Kitchen Foil / Tray", "metal", "ALU 41", "BLUE", "Clean food trays and baking foil."),
            SpecificWasteCandidate("item-generic-metal", "Other / Mixed Metal Scrap", "metal", null, "BLUE", "Pipes, wire, cutlery, metal bottle caps.")
        ),
        "cardboard" to listOf(
            SpecificWasteCandidate("item-corrugated-box", "Clean Corrugated Shipping Box", "cardboard", "PAP 20", "BLUE", "Flattened delivery and packaging cartons."),
            SpecificWasteCandidate("item-tetra-pak", "Aseptic Liquid Carton (Tetra Pak)", "cardboard", "C/PAP 84", "BLUE", "Milk and juice cartons with polyethylene/foil lining."),
            SpecificWasteCandidate("item-soiled-cardboard", "Greasy / Food-Soiled Cardboard", "cardboard", "PAP 20", "BLACK", "Oily pizza boxes, takeaway packaging. Contaminates clean paper."),
            SpecificWasteCandidate("item-cereal-box", "Paperboard Cereal / Shoe Box", "cardboard", "PAP 21", "BLUE", "Non-corrugated lightweight paperboard packaging.")
        ),
        "glass" to listOf(
            SpecificWasteCandidate("item-glass-bottle", "Glass Beverage Bottle", "glass", "GL 70", "BLUE", "Beer, wine, soda, and liquor glass bottles."),
            SpecificWasteCandidate("item-glass-jar", "Food Storage Glass Jar", "glass", "GL 72", "BLUE", "Jam, pickle, sauce jars. Rinse thoroughly."),
            SpecificWasteCandidate("item-broken-glass", "Broken Glass / Shards", "glass", "GL 70", "RED", "Laceration risk. Must wrap securely in paper/cardboard."),
            SpecificWasteCandidate("item-window-glass", "Window Pane / Mirror / Ceramic", "glass", null, "BLACK", "Treated non-container glass. Cannot mix with container recycling.")
        ),
        "paper" to listOf(
            SpecificWasteCandidate("item-office-paper", "Clean Office / Writing Paper", "paper", "PAP 20", "BLUE", "Documents, notebooks, printed mail."),
            SpecificWasteCandidate("item-newspaper", "Newspaper & Magazines", "paper", "PAP 22", "BLUE", "Dry newsprint, glossy flyers, periodicals."),
            SpecificWasteCandidate("item-shredded-paper", "Shredded Paper", "paper", "PAP 22", "BLUE", "Place in clear bag or compost bin."),
            SpecificWasteCandidate("item-tissue-paper", "Soiled Tissue / Paper Napkins", "paper", null, "GREEN", "Wet biodegradable tissue. Dispose in wet/organic waste.")
        ),
        "Battery" to listOf(
            SpecificWasteCandidate("item-battery-alkaline", "Alkaline AA / AAA / 9V Battery", "Battery", "BATTERY_ALKALINE", "RED", "Household cylindrical non-rechargeable cells."),
            SpecificWasteCandidate("item-battery-lithium", "Rechargeable Lithium-Ion Battery", "Battery", "BATTERY_LI_ION", "RED", "Phone, laptop, power tool battery. Thermal runaway hazard."),
            SpecificWasteCandidate("item-battery-button", "Button / Coin Cell (Lithium/Zinc)", "Battery", "BATTERY_BUTTON", "RED", "Watch, key fob, hearing aid button cells. Choking/burn hazard."),
            SpecificWasteCandidate("item-battery-lead-acid", "Lead-Acid Automotive / Inverter Battery", "Battery", "BATTERY_LEAD_ACID", "SPECIAL_DEPOT", "Heavy corrosive acid & toxic lead hazard.")
        ),
        "organic" to listOf(
            SpecificWasteCandidate("item-food-scraps", "Vegetable & Fruit Peels / Scraps", "organic", null, "GREEN", "Raw plant matter, peels, cores, coffee grounds."),
            SpecificWasteCandidate("item-cooked-food", "Cooked Leftover Food / Meat", "organic", null, "GREEN", "Domestic food waste for wet composting."),
            SpecificWasteCandidate("item-garden-waste", "Leaves, Flowers & Plant Prunings", "organic", null, "GREEN", "Dry leaves, pooja flower offerings, garden trim.")
        ),
        "Mobile" to listOf(
            SpecificWasteCandidate("item-smartphone", "Intact Smartphone / Feature Phone", "Mobile", "E_WASTE_MOBILE", "RED", "Backup data and bring to certified e-waste drop-off."),
            SpecificWasteCandidate("item-phone-battery", "Swollen / Loose Mobile Battery", "Mobile", "BATTERY_LI_ION", "RED", "Extreme fire hazard. Store in non-flammable container.")
        ),
        "Keyboard" to listOf(
            SpecificWasteCandidate("item-keyboard", "Computer Keyboard (Wired/Wireless)", "Keyboard", "E_WASTE_PERIPHERAL", "ORANGE", "Remove AAA/AA batteries if wireless. E-waste drop-off.")
        ),
        "Mouse" to listOf(
            SpecificWasteCandidate("item-mouse", "Computer Mouse (Optical/Trackball)", "Mouse", "E_WASTE_PERIPHERAL", "ORANGE", "Remove battery if wireless. E-waste collection.")
        ),
        "Microwave" to listOf(
            SpecificWasteCandidate("item-microwave", "Microwave Oven", "Microwave", "E_WASTE_LARGE", "SPECIAL_DEPOT", "Lethal high-voltage capacitor. Never disassemble.")
        ),
        "Television" to listOf(
            SpecificWasteCandidate("item-television", "Flat Panel / CRT Television", "Television", "E_WASTE_LARGE", "SPECIAL_DEPOT", "Toxic phosphors & heavy metals. Authorized e-waste depot.")
        ),
        "Printer" to listOf(
            SpecificWasteCandidate("item-printer", "Deskjet / Laser Printer", "Printer", "E_WASTE_OFFICE", "ORANGE", "Remove toner/ink cartridge before e-waste disposal.")
        ),
        "Washing Machine" to listOf(
            SpecificWasteCandidate("item-washing-machine", "Washing Machine / Dryer", "Washing Machine", "E_WASTE_WHITE_GOODS", "SPECIAL_DEPOT", "Heavy scrap recycling depot.")
        ),
        "PCB" to listOf(
            SpecificWasteCandidate("item-pcb", "Printed Circuit Board / Motherboard", "PCB", "E_WASTE_PCB", "RED", "Hazardous solder and precious metals. Regulated e-waste recycling.")
        ),
        "Player" to listOf(
            SpecificWasteCandidate("item-media-player", "Audio / Video / DVD Player", "Player", "E_WASTE_AUDIO", "ORANGE", "Consumer electronic waste drop-off.")
        ),
        "trash" to listOf(
            SpecificWasteCandidate("item-sanitary-waste", "Sanitary Pads / Diapers", "trash", null, "RED", "Domestic sanitary waste. Wrap in newspaper, red bin."),
            SpecificWasteCandidate("item-mixed-refuse", "Non-Recyclable Mixed Household Refuse", "trash", null, "BLACK", "Inert non-recyclable solid municipal refuse.")
        )
    )

    fun getCandidates(category: String): List<SpecificWasteCandidate> {
        val direct = CANDIDATES_BY_CATEGORY[category]
        if (direct != null) return direct

        val match = CANDIDATES_BY_CATEGORY.entries.firstOrNull { it.key.equals(category, ignoreCase = true) }
        return match?.value ?: emptyList()
    }

    fun findCandidateById(id: String): SpecificWasteCandidate? {
        return CANDIDATES_BY_CATEGORY.values.flatten().firstOrNull { it.id == id }
    }
}
