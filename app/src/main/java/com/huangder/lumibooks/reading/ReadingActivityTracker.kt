package com.huangder.lumibooks.reading

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.ReadingRecordEntity
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One owner for all elapsed time, independent of the reader and playback service lifetimes. */
@Singleton
class ReadingActivityTracker internal constructor(
    private val persist: suspend (ActivityIncrement) -> Unit,
    private val scope: CoroutineScope,
    private val timeline: ActivityTimeline
) {
    @Inject constructor(database: AppDatabase, identity: SyncIdentityStore) : this(
        { increment ->
            val device = identity.deviceId()
            database.withTransaction {
                val book = database.bookDao().getBookById(increment.bookId)
                if (book == null || book.isArchived) return@withTransaction
                val records = database.readingRecordDao()
                val old = records.getRecordByBookAndDate(increment.bookId, increment.date, device)
                records.insertRecord(
                    ReadingRecordEntity(
                        id = old?.id ?: 0,
                        bookId = increment.bookId,
                        date = increment.date,
                        duration = (old?.duration ?: 0) + increment.readingDuration,
                        listeningDuration = (old?.listeningDuration ?: 0) + increment.listeningDuration,
                        sourceDeviceId = device,
                        startTime = minOf(old?.startTime ?: increment.startTime, increment.startTime),
                        endTime = maxOf(old?.endTime ?: increment.endTime, increment.endTime),
                        updatedAt = maxOf(old?.updatedAt ?: 0, System.currentTimeMillis())
                    )
                )
            }
        },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ActivityTimeline(SystemClock::elapsedRealtime, System::currentTimeMillis)
    )
    private val pending = ArrayDeque<ActivityIncrement>()
    private var saving = false
    private var readerOwner: Any? = null

    init {
        scope.launch {
            while (true) {
                delay(15_000)
                enqueue(timeline.checkpoint())
            }
        }
    }

    fun setReaderVisible(owner: Any, bookId: String, visible: Boolean) {
        if (visible) {
            readerOwner = owner
            enqueue(timeline.setReadingBook(bookId))
        } else if (readerOwner === owner) {
            readerOwner = null
            enqueue(timeline.setReadingBook(null))
        }
    }

    fun setListeningBook(bookId: String?) {
        enqueue(timeline.setListeningBook(bookId))
    }

    private fun enqueue(increments: List<ActivityIncrement>) {
        // Coalesce waiting increments, including repeated transitions during an I/O failure.
        increments.forEach { increment ->
            val tail = pending.lastOrNull()
            if (tail != null && tail.bookId == increment.bookId && tail.date == increment.date) {
                pending.removeLast()
                pending.addLast(tail.copy(readingDuration = tail.readingDuration + increment.readingDuration,
                    listeningDuration = tail.listeningDuration + increment.listeningDuration,
                    endTime = increment.endTime))
            } else pending.addLast(increment)
        }
        if (saving || pending.isEmpty()) return
        saving = true
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    // Detach the batch so new activity never modifies an in-flight write.
                    val increment = pending.removeFirst()
                    try {
                        persist(increment)
                    } catch (error: Exception) {
                        pending.addFirst(increment)
                        if (error is CancellationException) throw error
                        Log.w("ReadingActivity", "Unable to save activity; retrying on next checkpoint", error)
                        break
                    }
                }
            } finally { saving = false }
        }
    }
}
