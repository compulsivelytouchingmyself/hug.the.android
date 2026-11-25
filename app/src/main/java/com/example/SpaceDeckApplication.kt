package com.example

import android.app.Application
import com.example.data.local.SpaceDeckDatabase
import com.example.media.MediaLibraryManager
import com.example.network.GradioNativeClient
import com.example.network.HfHubClient
import com.example.network.HybridSdkBridge
import com.example.network.SpaceDiscoveryEngine
import com.example.network.SpaceExecutionEngine
import com.example.security.HfOAuthManager
import com.example.security.SecureTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpaceDeckContainer(application: Application) {
    val database: SpaceDeckDatabase = SpaceDeckDatabase.getInstance(application)
    val tokenStore: SecureTokenStore = SecureTokenStore(application)
    val hubClient: HfHubClient = HfHubClient()
    val oauthManager: HfOAuthManager = HfOAuthManager(application, hubClient.okHttpClient)
    val gradioNativeClient: GradioNativeClient = GradioNativeClient()
    val hybridSdkBridge: HybridSdkBridge = HybridSdkBridge(application, hubClient.okHttpClient)
    val mediaLibraryManager: MediaLibraryManager = MediaLibraryManager(
        context = application,
        mediaAssetDao = database.mediaAssetDao(),
        okHttpClient = hubClient.okHttpClient
    )
    val discoveryEngine: SpaceDiscoveryEngine = SpaceDiscoveryEngine(
        hubClient = hubClient,
        gradioNativeClient = gradioNativeClient,
        hybridSdkBridge = hybridSdkBridge,
        manifestDao = database.manifestDao()
    )
    val executionEngine: SpaceExecutionEngine = SpaceExecutionEngine(
        context = application,
        hubClient = hubClient,
        hybridSdkBridge = hybridSdkBridge,
        gradioNativeClient = gradioNativeClient,
        mediaLibraryManager = mediaLibraryManager,
        jobRecordDao = database.jobRecordDao()
    )
}

class SpaceDeckApplication : Application() {
    lateinit var container: SpaceDeckContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = SpaceDeckContainer(this)
        // Warm up local bundled JS SDK WebView early on main thread
        container.hybridSdkBridge.ensureInitialized()
        // Never auto-resubmit uncertain jobs after app restart: mark them explicitly DISCONNECTED
        appScope.launch {
            container.database.jobRecordDao().markInterruptedJobsDisconnected(System.currentTimeMillis())
        }
    }
}
