package com.wasteinfoai.features.onboarding

enum class UserCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultRole: String,
    val badgeLabel: String,
    val loginTitle: String,
    val loginSubtitle: String,
    val idFieldLabel: String,
    val idFieldPlaceholder: String
) {
    HOUSEHOLDS(
        id = "Households",
        title = "Households",
        subtitle = "Residents, apartments, community recycling & domestic waste segregation",
        defaultRole = "RESIDENT",
        badgeLabel = "Citizen / Resident",
        loginTitle = "Household Resident Sign In",
        loginSubtitle = "Log in to track your recycling score, disposal history & community hotspot reports.",
        idFieldLabel = "Email Address",
        idFieldPlaceholder = "resident@example.com"
    ),
    MUNICIPAL_WORKER(
        id = "Municipal worker",
        title = "Municipal worker",
        subtitle = "Field sanitation staff, waste collectors & municipal cleanup crews",
        defaultRole = "SANITATION_WORKER",
        badgeLabel = "Field Sanitation",
        loginTitle = "Municipal Staff Portal",
        loginSubtitle = "Authenticate with your Sanitation Worker ID to view daily cleanup routes and assigned tasks.",
        idFieldLabel = "Worker ID or Official Email",
        idFieldPlaceholder = "MW-7482 or staff@citywaste.gov"
    ),
    INDUSTRIES(
        id = "Industries",
        title = "Industries",
        subtitle = "Commercial facilities, manufacturing plants & hazardous bulk waste generators",
        defaultRole = "INSTITUTION",
        badgeLabel = "Commercial & Industrial",
        loginTitle = "Industry & Facility Portal",
        loginSubtitle = "Sign in to log industrial bulk waste manifests, track hazardous compliance & audit certificates.",
        idFieldLabel = "Facility ID / Corporate Email",
        idFieldPlaceholder = "IND-104 or plant.manager@company.com"
    ),
    OTHER(
        id = "Other",
        title = "Other",
        subtitle = "Auditors, environmental inspectors, system administrators & visitors",
        defaultRole = "REVIEWER",
        badgeLabel = "Auditors & Public",
        loginTitle = "Inspector & Operations Portal",
        loginSubtitle = "Access verification queues, systemic audit metrics, and city-wide administrative control.",
        idFieldLabel = "Staff / Admin Email",
        idFieldPlaceholder = "inspector@wasteinfo.ai"
    );

    companion object {
        fun fromId(id: String): UserCategory {
            return entries.find { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) } ?: HOUSEHOLDS
        }
    }
}
