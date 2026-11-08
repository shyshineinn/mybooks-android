package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal enum class MyBooksWorkerDecision { SUCCESS, RETRY, STOP }
internal fun myBooksWorkerDecision(result: MyBooksSyncResult, attempt: Int): MyBooksWorkerDecision = when {
    result.success -> MyBooksWorkerDecision.SUCCESS
    result.failure == MyBooksSyncFailure.TRANSIENT && attempt < 4 -> MyBooksWorkerDecision.RETRY
    else -> MyBooksWorkerDecision.STOP
}

data class MyBooksSyncWorkState(val queued: Boolean = false, val retrying: Boolean = false)

@HiltWorker
class MyBooksSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: MyBooksSyncManager,
    private val stateStore: MyBooksSyncStateStore,
    private val preferences: MyBooksSyncPreferencesStore
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!preferences.automatic()) return Result.success()
        if (!MyBooksConfig.hasWriteToken() || inputData.getString(MyBooksSyncScheduler.SERVER_KEY) != MyBooksConfig.SERVER_URL) {
            return Result.success()
        }
        if (stateStore.status.first().failure == MyBooksSyncFailure.AUTH) return Result.success()
        val result = syncManager.syncNow(scheduleRetry = false)
        return when (myBooksWorkerDecision(result, runAttemptCount)) {
            MyBooksWorkerDecision.SUCCESS -> Result.success()
            MyBooksWorkerDecision.RETRY -> Result.retry()
            MyBooksWorkerDecision.STOP -> {
                stateStore.retryExhausted(result.attemptAt)
                // The error is persisted for the UI. Completing this handled attempt keeps
                // a queued newer edit from being cancelled through a failed predecessor.
                Result.success()
            }
        }
    }
}

internal fun canEnqueueMyBooksSync(unfinished: List<WorkInfo.State>, followUpIfRunning: Boolean): Boolean =
    unfinished.isEmpty() || (followUpIfRunning && unfinished.all { it == WorkInfo.State.RUNNING })

/** Network-constrained, durable requests; retain at most one successor to an active sync. */
@Singleton
class MyBooksSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateStore: MyBooksSyncStateStore,
    private val preferences: MyBooksSyncPreferencesStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enqueueMutex = Mutex()
    val workState get() = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val pending = infos.filter { !it.state.isFinished }
        MyBooksSyncWorkState(
            queued = pending.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
            retrying = pending.any { it.runAttemptCount > 0 }
        )
    }

    fun schedulePush() { scope.launch { enqueue(5_000, followUpIfRunning = true) } }
    fun scheduleImportedBooks() { scope.launch { enqueue(0, followUpIfRunning = true) } }
    fun scheduleImmediate() { scope.launch { enqueue(0) } }
    fun scheduleRetry() { scope.launch { enqueue(30_000) } }
    suspend fun cancel() = withContext(Dispatchers.IO) {
        enqueueMutex.withLock { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get() }
        Unit
    }

    private suspend fun enqueue(delayMs: Long, followUpIfRunning: Boolean = false) = enqueueMutex.withLock {
        if (!preferences.automatic() || !MyBooksConfig.hasWriteToken() || stateStore.status.first().failure == MyBooksSyncFailure.AUTH) return@withLock
        val manager = WorkManager.getInstance(context)
        val unfinished = manager.getWorkInfosForUniqueWork(WORK_NAME).get().filter { !it.state.isFinished }
        // An enqueued/backing-off successor will read all current edits when it starts.
        if (!canEnqueueMyBooksSync(unfinished.map { it.state }, followUpIfRunning)) return@withLock
        val request = OneTimeWorkRequestBuilder<MyBooksSyncWorker>()
            .setInputData(workDataOf(SERVER_KEY to MyBooksConfig.SERVER_URL))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
    }

    companion object {
        internal const val WORK_NAME = "mybooks_server_sync"
        internal const val SERVER_KEY = "server_url"
    }
}
