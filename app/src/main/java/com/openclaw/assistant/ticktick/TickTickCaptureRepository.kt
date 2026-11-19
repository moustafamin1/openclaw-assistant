package com.openclaw.assistant.ticktick

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.data.local.AppDatabase
import com.openclaw.assistant.data.local.entity.PendingTickTickTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class CaptureSaveOutcome {
    data class Synced(val title: String, val taskId: String) : CaptureSaveOutcome()
    data class QueuedOffline(val title: String, val reason: String) : CaptureSaveOutcome()
    data class AuthRequired(val title: String, val reason: String) : CaptureSaveOutcome()
    object IgnoredBlank : CaptureSaveOutcome()
}

class TickTickCaptureRepository(
    context: Context,
    private val apiClient: TickTickApiClient = TickTickApiClient(),
) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).pendingTickTickTaskDao()
    private val settings = SettingsRepository.getInstance(appContext)
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var networkCallbackRegistered = false

    fun observePendingTasks(): Flow<List<PendingTickTickTaskEntity>> = dao.observeAll()

    fun observePendingCount(): Flow<Int> = dao.observeCount()

    suspend fun enqueueAndSync(rawTranscript: String): CaptureSaveOutcome = withContext(Dispatchers.IO) {
        val contextIndex = ContextIndexManager.getIndex(appContext)
        val cleanTitle = cleanTranscript(rawTranscript, settings.effectiveTickTickVocabulary(), contextIndex)
        if (cleanTitle.isEmpty()) return@withContext CaptureSaveOutcome.IgnoredBlank

        val rowId = dao.insert(
            PendingTickTickTaskEntity(
                title = cleanTitle,
                projectId = "inbox",
                priority = 0,
                status = PendingTickTickTaskEntity.STATUS_PENDING,
            ),
        )

        val token = settings.tickTickAccessToken
        if (token.isBlank()) {
            dao.markFailed(rowId, PendingTickTickTaskEntity.STATUS_AUTH_ERROR, "Missing TickTick token")
            return@withContext CaptureSaveOutcome.AuthRequired(cleanTitle, "TickTick token not configured")
        }

        syncSingleRow(rowId, cleanTitle, token)
    }

    suspend fun flushPending(): Int = syncMutex.withLock {
        val token = settings.tickTickAccessToken
        if (token.isBlank()) return@withLock 0

        dao.resetAuthErrors()
        val items = dao.getAllOrdered()
        var syncedCount = 0
        for (item in items) {
            when (val outcome = attemptUpload(item.id, item.title, item.projectId, item.priority, token)) {
                is CaptureSaveOutcome.Synced -> syncedCount++
                is CaptureSaveOutcome.AuthRequired -> break
                is CaptureSaveOutcome.QueuedOffline, CaptureSaveOutcome.IgnoredBlank -> {
                    ensureNetworkSyncCallback()
                }
            }
        }
        syncedCount
    }

    private suspend fun syncSingleRow(rowId: Long, title: String, token: String): CaptureSaveOutcome =
        syncMutex.withLock {
            val outcome = attemptUpload(rowId, title, "inbox", 0, token)
            if (outcome is CaptureSaveOutcome.QueuedOffline) {
                ensureNetworkSyncCallback()
            }
            outcome
        }

    private suspend fun attemptUpload(
        rowId: Long,
        title: String,
        projectId: String,
        priority: Int,
        token: String,
    ): CaptureSaveOutcome {
        return when (val result = apiClient.createInboxTask(token, title, projectId, priority)) {
            is TickTickCreateResult.Success -> {
                dao.deleteById(rowId)
                CaptureSaveOutcome.Synced(result.title, result.taskId)
            }
            is TickTickCreateResult.Unauthorized -> {
                dao.markFailed(rowId, PendingTickTickTaskEntity.STATUS_AUTH_ERROR, result.message)
                CaptureSaveOutcome.AuthRequired(title, "TickTick token unauthorized")
            }
            is TickTickCreateResult.HttpError -> {
                dao.markFailed(rowId, PendingTickTickTaskEntity.STATUS_PENDING, "HTTP ${result.statusCode}: ${result.message}")
                CaptureSaveOutcome.QueuedOffline(title, "HTTP ${result.statusCode}")
            }
            is TickTickCreateResult.NetworkError -> {
                dao.markFailed(rowId, PendingTickTickTaskEntity.STATUS_PENDING, result.message)
                CaptureSaveOutcome.QueuedOffline(title, "Offline")
            }
        }
    }

    private fun ensureNetworkSyncCallback() {
        if (networkCallbackRegistered) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch {
                            flushPending()
                        }
                    }
                },
            )
            networkCallbackRegistered = true
        }
    }

    companion object {
        @Volatile
        private var instance: TickTickCaptureRepository? = null

        fun getInstance(context: Context): TickTickCaptureRepository {
            return instance ?: synchronized(this) {
                instance ?: TickTickCaptureRepository(context.applicationContext).also { instance = it }
            }
        }

        fun cleanTranscript(
            raw: String,
            customVocabulary: String = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY,
            contextIndex: ContextIndex? = null,
        ): String {
            return TranscriptNormalizer.normalize(raw, customVocabulary, contextIndex)
        }
    }
}
