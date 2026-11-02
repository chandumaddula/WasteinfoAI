package com.wasteinfoai.features.onboarding

import androidx.annotation.StringRes
import com.wasteinfoai.R

enum class UserCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultRole: String,
    val badgeLabel: String,
    val loginTitle: String,
    val loginSubtitle: String,
    val idFieldLabel: String,
    val idFieldPlaceholder: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int
) {
    HOUSEHOLD_RECYCLING(
        id = "Household recycling",
        title = "Household recycling",
        subtitle = "Residents, apartments, domestic waste segregation & curbside sorting",
        defaultRole = "RESIDENT",
        badgeLabel = "Household / Resident",
        loginTitle = "Household Recycling Sign In",
        loginSubtitle = "Log in to track your recycling score, disposal history & domestic waste segregation.",
        idFieldLabel = "Email Address",
        idFieldPlaceholder = "resident@example.com",
        titleRes = R.string.category_household_title,
        subtitleRes = R.string.category_household_desc
    ),
    E_WASTE_MANAGEMENT(
        id = "E-waste management",
        title = "E-waste management",
        subtitle = "Electronics, circuitry, batteries, hazardous scrap & certified e-waste drop-offs",
        defaultRole = "SANITATION_WORKER",
        badgeLabel = "E-Waste Specialist",
        loginTitle = "E-Waste Management Portal",
        loginSubtitle = "Authenticate with your Specialist ID to view e-waste collection routes and tasks.",
        idFieldLabel = "Specialist ID or Official Email",
        idFieldPlaceholder = "EW-7482 or staff@citywaste.gov",
        titleRes = R.string.category_ewaste_title,
        subtitleRes = R.string.category_ewaste_desc
    ),
    COMMUNITY_RECYCLING(
        id = "Community recycling",
        title = "Community recycling",
        subtitle = "Neighborhood hubs, local recycling drives & community collection initiatives",
        defaultRole = "RESIDENT",
        badgeLabel = "Community Hub",
        loginTitle = "Community Recycling Portal",
        loginSubtitle = "Sign in to coordinate community recycling drop points and neighborhood cleanup drives.",
        idFieldLabel = "Community Lead Email",
        idFieldPlaceholder = "community@wasteinfo.ai",
        titleRes = R.string.category_community_title,
        subtitleRes = R.string.category_community_desc
    ),
    PLASTIC_WASTE_REDUCTION(
        id = "Plastic waste reduction",
        title = "Plastic waste reduction",
        subtitle = "Single-use plastics, packaging minimization & commercial recycling compliance",
        defaultRole = "INSTITUTION",
        badgeLabel = "Plastic Reduction",
        loginTitle = "Plastic Waste Reduction Portal",
        loginSubtitle = "Sign in to log plastic audit manifests, track reduction compliance & certificates.",
        idFieldLabel = "Facility ID / Corporate Email",
        idFieldPlaceholder = "PWR-104 or plant.manager@company.com",
        titleRes = R.string.category_plastic_title,
        subtitleRes = R.string.category_plastic_desc
    ),
    SUSTAINABILITY_EDUCATION(
        id = "Sustainability education",
        title = "Sustainability education",
        subtitle = "Schools, universities, public awareness campaigns & sustainability research",
        defaultRole = "REVIEWER",
        badgeLabel = "Education & Audit",
        loginTitle = "Sustainability Education Portal",
        loginSubtitle = "Access educational curriculums, sustainability guides & environmental audit metrics.",
        idFieldLabel = "Staff / Educator Email",
        idFieldPlaceholder = "educator@wasteinfo.ai",
        titleRes = R.string.category_sustainability_title,
        subtitleRes = R.string.category_sustainability_desc
    );

    companion object {
        // Backwards-compatible aliases
        val HOUSEHOLDS get() = HOUSEHOLD_RECYCLING
        val MUNICIPAL_WORKER get() = E_WASTE_MANAGEMENT
        val INDUSTRIES get() = PLASTIC_WASTE_REDUCTION
        val OTHER get() = SUSTAINABILITY_EDUCATION

        fun fromId(id: String): UserCategory {
            val trimmed = id.trim()
            return entries.find {
                it.id.equals(trimmed, ignoreCase = true) ||
                it.title.equals(trimmed, ignoreCase = true) ||
                it.name.equals(trimmed, ignoreCase = true)
            } ?: when (trimmed.lowercase()) {
                "households", "household", "household recycling" -> HOUSEHOLD_RECYCLING
                "municipal worker", "municipal_worker", "e-waste", "e-waste management", "ewaste" -> E_WASTE_MANAGEMENT
                "community", "community recycling" -> COMMUNITY_RECYCLING
                "industries", "industry", "plastic", "plastic waste reduction" -> PLASTIC_WASTE_REDUCTION
                "sustainability education", "education", "other", "reviewer" -> SUSTAINABILITY_EDUCATION
                else -> HOUSEHOLD_RECYCLING
            }
        }

        fun isWasteLocationCategory(categoryName: String?): Boolean {
            val norm = categoryName?.trim()?.lowercase() ?: return false
            return norm.contains("community") || norm.contains("plastic")
        }
    }

    /**
     * True only for categories that require waste accumulation location tracking
     * and area sanitization: Community Recycling and Plastic Waste Reduction.
     */
    val hasWasteLocationFeatures: Boolean
        get() = this == COMMUNITY_RECYCLING || this == PLASTIC_WASTE_REDUCTION
}
