package com.wasteinfoai.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.wasteinfoai.camera.CameraRoute
import com.wasteinfoai.core.di.AppContainer
import com.wasteinfoai.features.assistant.AssistantScreen
import com.wasteinfoai.features.assistant.AssistantViewModel
import com.wasteinfoai.features.auth.LoginScreen
import com.wasteinfoai.features.auth.LoginViewModel
import com.wasteinfoai.features.dashboards.AdminDashboardScreen
import com.wasteinfoai.features.dashboards.AdminDashboardViewModel
import com.wasteinfoai.features.dashboards.ReviewerDashboardScreen
import com.wasteinfoai.features.dashboards.ReviewerDashboardViewModel
import com.wasteinfoai.features.history.HistoryScreen
import com.wasteinfoai.features.history.HistoryViewModel
import com.wasteinfoai.features.home.HomeScreen
import com.wasteinfoai.features.home.HomeViewModel
import com.wasteinfoai.features.map.MapScreen
import com.wasteinfoai.features.map.MapViewModel
import com.wasteinfoai.features.reports.CreateReportScreen
import com.wasteinfoai.features.reports.CreateReportViewModel
import com.wasteinfoai.features.scan.ScanResultScreen
import com.wasteinfoai.features.scan.ScanResultViewModel
import com.wasteinfoai.features.dashboards.IndustryDashboardScreen
import com.wasteinfoai.features.onboarding.CategoryAuthScreen
import com.wasteinfoai.features.onboarding.CategorySelectionScreen
import com.wasteinfoai.features.onboarding.SplashScreen
import com.wasteinfoai.features.tasks.WorkerTasksScreen
import com.wasteinfoai.features.tasks.WorkerTasksViewModel
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun AppNavGraph(
    navController: NavHostController,
    container: AppContainer
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        // 1. App Startup: Logo, Get Started & Quick Scan
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToCategorySelection = {
                    navController.navigate(Screen.CategorySelection.route)
                },
                onQuickScan = {
                    navController.navigate(Screen.Camera.route)
                }
            )
        }

        // 2. Category Selection Screen (Condition: Do NOT show selected option initially)
        composable(Screen.CategorySelection.route) {
            CategorySelectionScreen(
                initialSelectedCategory = null,
                onCategorySelected = { category ->
                    navController.navigate(Screen.CategoryAuth.createRoute(category.id))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 3. Category-Based Authentication Screen
        composable(
            route = Screen.CategoryAuth.route,
            arguments = listOf(navArgument("category") { type = NavType.StringType; defaultValue = "Household recycling" })
        ) { backStackEntry ->
            val cat = URLDecoder.decode(backStackEntry.arguments?.getString("category") ?: "Household recycling", StandardCharsets.UTF_8.toString())
            val vm = rememberViewModel {
                LoginViewModel(
                    authRepository = container.authRepository,
                    sessionManager = container.sessionManager
                )
            }
            CategoryAuthScreen(
                categoryName = cat,
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDashboard = { selectedCat ->
                    val lower = selectedCat.lowercase()
                    val target = when {
                        lower.contains("worker") || lower.contains("e-waste") || lower.contains("sanitation") -> Screen.Tasks.route
                        lower.contains("industr") || lower.contains("plastic") || lower.contains("institution") -> Screen.IndustryDashboard.route
                        lower.contains("education") || lower.contains("reviewer") || lower.contains("other") -> Screen.ReviewerDashboard.route
                        lower.contains("admin") -> Screen.AdminDashboard.route
                        else -> Screen.Home.route
                    }
                    navController.navigate(target) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // 4. Dedicated Industry Dashboard
        composable(Screen.IndustryDashboard.route) {
            IndustryDashboardScreen(
                sessionManager = container.sessionManager,
                onNavigateToCamera = { navController.navigate(Screen.Camera.route) },
                onNavigateToCreateReport = {
                    navController.navigate(Screen.CreateReport.createRoute("Hazardous Waste"))
                },
                onNavigateToMap = { navController.navigate(Screen.Map.route) },
                onNavigateToAssistant = {
                    navController.navigate(Screen.Assistant.createRoute("Industrial Waste & Compliance"))
                },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToCategorySelection = {
                    navController.navigate(Screen.CategorySelection.route)
                },
                onNavigateBack = { navController.popBackStack() },
                onLogout = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Home Dashboard (Households / Resident Dashboard)
        composable(Screen.Home.route) {
            val vm = rememberViewModel {
                HomeViewModel(
                    scanRepository = container.scanRepository,
                    reportRepository = container.reportRepository,
                    sessionManager = container.sessionManager,
                    syncScheduler = container.syncScheduler
                )
            }
            HomeScreen(
                viewModel = vm,
                sessionManager = container.sessionManager,
                onNavigateToCamera = { navController.navigate(Screen.Camera.route) },
                onNavigateToCreateReport = { navController.navigate(Screen.CreateReport.createRoute()) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToMap = { navController.navigate(Screen.Map.route) },
                onNavigateToAssistant = { navController.navigate(Screen.Assistant.createRoute()) },
                onNavigateToTasks = { navController.navigate(Screen.Tasks.route) },
                onNavigateToReviewer = { navController.navigate(Screen.ReviewerDashboard.route) },
                onNavigateToAdmin = { navController.navigate(Screen.AdminDashboard.route) },
                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                onNavigateToCategorySelection = { navController.navigate(Screen.CategorySelection.route) },
                onNavigateToIndustry = null,
                onLogout = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Camera & Gallery Capture Flow
        composable(Screen.Camera.route) {
            CameraRoute(
                onImageConfirmed = { validatedImage ->
                    val path = validatedImage.file.absolutePath
                    navController.navigate(Screen.ScanResult.createRoute(path)) {
                        popUpTo(Screen.Camera.route) { inclusive = true }
                    }
                },
                onClose = { navController.popBackStack() }
            )
        }

        // Classification & Disposal Guidance Results
        composable(
            route = Screen.ScanResult.route,
            arguments = listOf(navArgument("imagePath") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val rawPath = backStackEntry.arguments?.getString("imagePath") ?: ""
            val imagePath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.toString())
            val vm = rememberViewModel {
                ScanResultViewModel(
                    wasteClassifier = container.wasteClassifier,
                    disposalRuleEngine = container.disposalRuleEngine,
                    scanRepository = container.scanRepository,
                    ttsManager = container.textToSpeechManager,
                    feedbackRepository = container.feedbackRepository,
                    locationHelper = container.locationHelper
                )
            }
            ScanResultScreen(
                imagePath = imagePath,
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCreateReport = { category, path ->
                    navController.navigate(Screen.CreateReport.createRoute(category, path))
                },
                onNavigateToAssistant = { category ->
                    navController.navigate(Screen.Assistant.createRoute(category))
                }
            )
        }

        // Create Report
        composable(
            route = Screen.CreateReport.route,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType; defaultValue = "" },
                navArgument("imagePath") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val cat = URLDecoder.decode(backStackEntry.arguments?.getString("category") ?: "", StandardCharsets.UTF_8.toString())
            val img = URLDecoder.decode(backStackEntry.arguments?.getString("imagePath") ?: "", StandardCharsets.UTF_8.toString())
            val vm = rememberViewModel {
                CreateReportViewModel(
                    reportRepository = container.reportRepository,
                    locationHelper = container.locationHelper,
                    syncScheduler = container.syncScheduler
                )
            }
            CreateReportScreen(
                initialCategory = cat,
                initialImagePath = img,
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // History
        composable(Screen.History.route) {
            val vm = rememberViewModel { HistoryViewModel(container.scanRepository) }
            HistoryScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Map
        composable(Screen.Map.route) {
            val vm = rememberViewModel { MapViewModel(container.reportRepository) }
            MapScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCreateReport = { navController.navigate(Screen.CreateReport.createRoute()) }
            )
        }

        // Assistant
        composable(
            route = Screen.Assistant.route,
            arguments = listOf(navArgument("category") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val cat = URLDecoder.decode(backStackEntry.arguments?.getString("category") ?: "", StandardCharsets.UTF_8.toString())
            val vm = rememberViewModel {
                AssistantViewModel(
                    assistantService = container.assistantService,
                    ttsManager = container.textToSpeechManager
                )
            }
            AssistantScreen(
                initialCategory = cat,
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Worker Tasks
        composable(Screen.Tasks.route) {
            val vm = rememberViewModel { WorkerTasksViewModel(container.taskRepository) }
            WorkerTasksScreen(
                viewModel = vm,
                sessionManager = container.sessionManager,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCamera = { navController.navigate(Screen.Camera.route) },
                onNavigateToMap = { navController.navigate(Screen.Map.route) },
                onNavigateToCategorySelection = { navController.navigate(Screen.CategorySelection.route) },
                onLogout = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Reviewer Dashboard
        composable(Screen.ReviewerDashboard.route) {
            val vm = rememberViewModel { ReviewerDashboardViewModel(container.reportRepository) }
            ReviewerDashboardScreen(
                viewModel = vm,
                sessionManager = container.sessionManager,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCategorySelection = { navController.navigate(Screen.CategorySelection.route) },
                onLogout = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Admin Dashboard
        composable(Screen.AdminDashboard.route) {
            val vm = rememberViewModel { AdminDashboardViewModel(container.reportRepository) }
            AdminDashboardScreen(
                viewModel = vm,
                sessionManager = container.sessionManager,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToCategorySelection = { navController.navigate(Screen.CategorySelection.route) },
                onLogout = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Login / Account
        composable(Screen.Login.route) {
            val vm = rememberViewModel {
                LoginViewModel(
                    authRepository = container.authRepository,
                    sessionManager = container.sessionManager
                )
            }
            LoginScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDashboard = { roleOrCat ->
                    val lower = roleOrCat.lowercase()
                    val target = when {
                        lower.contains("worker") || lower.contains("e-waste") || lower.contains("sanitation") -> Screen.Tasks.route
                        lower.contains("industr") || lower.contains("plastic") || lower.contains("institution") -> Screen.IndustryDashboard.route
                        lower.contains("education") || lower.contains("reviewer") || lower.contains("other") -> Screen.ReviewerDashboard.route
                        lower.contains("admin") -> Screen.AdminDashboard.route
                        else -> Screen.Home.route
                    }
                    navController.navigate(target) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onLogoutSuccess = {
                    container.sessionManager.clearSession()
                    navController.navigate(Screen.Splash.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

@Composable
inline fun <reified T : androidx.lifecycle.ViewModel> rememberViewModel(
    crossinline creator: () -> T
): T {
    return androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM {
                return creator() as VM
            }
        }
    )
}
