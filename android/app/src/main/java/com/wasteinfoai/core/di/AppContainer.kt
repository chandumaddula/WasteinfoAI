package com.wasteinfoai.core.di

import android.content.Context
import com.wasteinfoai.ai.llm.AssistantService
import com.wasteinfoai.ai.llm.CloudAssistantClient
import com.wasteinfoai.ai.llm.LlamaEngine
import com.wasteinfoai.ai.llm.LlmModelManager
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.vision.WasteClassifier
import com.wasteinfoai.audio.SpeechInputHelper
import com.wasteinfoai.audio.TextToSpeechManager
import com.wasteinfoai.data.local.WasteDatabase
import com.wasteinfoai.data.remote.ApiClient
import com.wasteinfoai.data.remote.ApiService
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.data.repository.AuthRepository
import com.wasteinfoai.data.repository.ReportRepository
import com.wasteinfoai.data.repository.ScanRepository
import com.wasteinfoai.data.repository.TaskRepository
import com.wasteinfoai.location.LocationHelper
import com.wasteinfoai.sync.SyncScheduler

/**
 * Dependency container providing lazily instantiated application services and repositories.
 */
class AppContainer(private val context: Context) {

    val wasteDatabase: WasteDatabase by lazy {
        WasteDatabase.getInstance(context)
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(context)
    }

    val apiService: ApiService by lazy {
        ApiClient.create(sessionManager)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(apiService, sessionManager)
    }

    val scanRepository: ScanRepository by lazy {
        ScanRepository(wasteDatabase.scanDao())
    }

    val feedbackRepository: com.wasteinfoai.data.repository.FeedbackRepository by lazy {
        com.wasteinfoai.data.repository.FeedbackRepository(wasteDatabase.feedbackDao())
    }

    val reportRepository: ReportRepository by lazy {
        ReportRepository(wasteDatabase.reportDao(), apiService)
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(wasteDatabase.taskDao(), apiService)
    }

    val wasteClassifier: WasteClassifier by lazy {
        WasteClassifier(context)
    }

    val disposalRuleEngine: DisposalRuleEngine by lazy {
        DisposalRuleEngine(context)
    }

    val llamaEngine: LlamaEngine by lazy {
        LlamaEngine(context)
    }

    val cloudAssistantClient: CloudAssistantClient by lazy {
        CloudAssistantClient(context)
    }

    val llmModelManager: LlmModelManager by lazy {
        LlmModelManager(context)
    }

    val speechInputHelper: SpeechInputHelper by lazy {
        SpeechInputHelper(context)
    }

    val assistantService: AssistantService by lazy {
        AssistantService(
            llamaEngine = llamaEngine,
            ruleEngine = disposalRuleEngine,
            cloudClient = cloudAssistantClient
        )
    }

    val textToSpeechManager: TextToSpeechManager by lazy {
        TextToSpeechManager(context)
    }

    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(context)
    }

    val locationHelper: LocationHelper by lazy {
        LocationHelper(context)
    }
}
