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
        title = "Home / Citizen",
        subtitle = "",
        defaultRole = "RESIDENT",
        badgeLabel = "Home / Citizen",
        loginTitle = "Home / Citizen Sign In",
        loginSubtitle = "Sign in to track your recycling score and waste segregation.",
        idFieldLabel = "Phone Number or Name",
        idFieldPlaceholder = "e.g. 9876543210 or Rahul",
        titleRes = R.string.category_household_title,
        subtitleRes = R.string.category_household_desc
    ),
    E_WASTE_MANAGEMENT(
        id = "E-waste management",
        title = "E-waste management",
        subtitle = "",
        defaultRole = "SANITATION_WORKER",
        badgeLabel = "E-waste management",
        loginTitle = "E-waste management Portal",
        loginSubtitle = "Sign in to view e-waste collection routes and tasks.",
        idFieldLabel = "Phone Number or Name",
        idFieldPlaceholder = "e.g. 9876543210 or Staff ID",
        titleRes = R.string.category_ewaste_title,
        subtitleRes = R.string.category_ewaste_desc
    ),
    COMMUNITY_RECYCLING(
        id = "Community recycling",
        title = "Colony / Society",
        subtitle = "",
        defaultRole = "RESIDENT",
        badgeLabel = "Colony / Society",
        loginTitle = "Colony / Society Portal",
        loginSubtitle = "Sign in to coordinate community recycling drop points and cleanups.",
        idFieldLabel = "Phone Number or Name",
        idFieldPlaceholder = "e.g. 9876543210 or Society Lead",
        titleRes = R.string.category_community_title,
        subtitleRes = R.string.category_community_desc
    ),
    PLASTIC_WASTE_REDUCTION(
        id = "Plastic waste reduction",
        title = "Shop & Office",
        subtitle = "",
        defaultRole = "INSTITUTION",
        badgeLabel = "Shop & Office",
        loginTitle = "Shop & Office Portal",
        loginSubtitle = "Sign in to log waste segregation and recycling certificates.",
        idFieldLabel = "Phone Number or Name",
        idFieldPlaceholder = "e.g. 9876543210 or Store Name",
        titleRes = R.string.category_plastic_title,
        subtitleRes = R.string.category_plastic_desc
    ),
    SUSTAINABILITY_EDUCATION(
        id = "Sustainability education",
        title = "School & Students",
        subtitle = "",
        defaultRole = "REVIEWER",
        badgeLabel = "School & Students",
        loginTitle = "School & Students Portal",
        loginSubtitle = "Access educational curriculums and environmental audit metrics.",
        idFieldLabel = "Phone Number or Name",
        idFieldPlaceholder = "e.g. 9876543210 or Student ID",
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
                "households", "household", "household recycling", "home / citizen", "home", "citizen" -> HOUSEHOLD_RECYCLING
                "municipal worker", "municipal_worker", "e-waste", "e-waste management", "ewaste" -> E_WASTE_MANAGEMENT
                "community", "community recycling", "colony / society", "colony", "society" -> COMMUNITY_RECYCLING
                "industries", "industry", "plastic", "plastic waste reduction", "shop & office", "shop", "office" -> PLASTIC_WASTE_REDUCTION
                "sustainability education", "education", "school & students", "school", "students", "other", "reviewer" -> SUSTAINABILITY_EDUCATION
                else -> HOUSEHOLD_RECYCLING
            }
        }

        fun isWasteLocationCategory(categoryName: String?): Boolean {
            val norm = categoryName?.trim()?.lowercase() ?: return false
            return norm.contains("community") || norm.contains("plastic") || norm.contains("colony") || norm.contains("society") || norm.contains("shop") || norm.contains("office")
        }
    }

    /**
     * True only for categories that require waste accumulation location tracking
     * and area sanitization: Community Recycling and Plastic Waste Reduction.
     */
    val hasWasteLocationFeatures: Boolean
        get() = this == COMMUNITY_RECYCLING || this == PLASTIC_WASTE_REDUCTION
}
