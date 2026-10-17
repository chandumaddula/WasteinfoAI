package com.wasteinfoai.core.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object History : Screen("history")
    data object Map : Screen("map")
    data object Tasks : Screen("tasks")
    data object ReviewerDashboard : Screen("reviewer_dashboard")
    data object AdminDashboard : Screen("admin_dashboard")
    data object Login : Screen("login")

    data object ScanResult : Screen("scan_result?imagePath={imagePath}") {
        fun createRoute(imagePath: String): String {
            val encoded = URLEncoder.encode(imagePath, StandardCharsets.UTF_8.toString())
            return "scan_result?imagePath=$encoded"
        }
    }

    data object CreateReport : Screen("create_report?category={category}&imagePath={imagePath}") {
        fun createRoute(category: String = "", imagePath: String = ""): String {
            val encCat = URLEncoder.encode(category, StandardCharsets.UTF_8.toString())
            val encImg = URLEncoder.encode(imagePath, StandardCharsets.UTF_8.toString())
            return "create_report?category=$encCat&imagePath=$encImg"
        }
    }

    data object Assistant : Screen("assistant?category={category}") {
        fun createRoute(category: String = ""): String {
            val encCat = URLEncoder.encode(category, StandardCharsets.UTF_8.toString())
            return "assistant?category=$encCat"
        }
    }
}
