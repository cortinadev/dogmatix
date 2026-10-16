package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadTrackDao
import com.cortinadev.dogmatix.data.local.entity.DownloadResumeEntity
import com.cortinadev.dogmatix.data.local.entity.SourceStatEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.DownloadOrigins
import com.cortinadev.dogmatix.util.OriginStats
import com.cortinadev.dogmatix.util.SourceReliability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SourceStatsService"
/** Resume records of transfers nobody came back to are dropped after this long. */
private const val RESUME_KEEP_MS = 30L * 24 * 60 * 60 * 1000

/** Bytes one attempt moved and how long the transfer took (extraction not included), read when it ends. */
class TransferMeter {
    @Volatile var bytes: Long = 0L
    @Volatile var millis: Long = 0L
}

/**
 * How each download origin has been doing (see [SourceReliability]): every attempt reports here,
 * the mirrors of a web download are tried best first, the library breaks ties between copies of
 * one release with it, and Sources shows it under each address.
 */
@Singleton
class SourceStatsService @Inject constructor(
    private val dao: DownloadTrackDao,
    settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()
    private val lock = Mutex()

    private val _stats = MutableStateFlow<Map<String, OriginStats>>(emptyMap())
    /** Track record per origin (see [DownloadOrigins]). */
    val stats: StateFlow<Map<String, OriginStats>> = _stats.asStateFlow()

    private val _rommBaseUrl = MutableStateFlow("")
    /** The RomM server, whose host `romm://` sources are recorded under. */
    val rommBaseUrl: StateFlow<String> = _rommBaseUrl.asStateFlow()

    init {
        scope.launch {
            runCatching { dao.allSourceStats() }
                .onSuccess { rows -> _stats.update { current -> rows.associate { it.origin to it.toStats() } + current } }
                .onFailure { Log.w(TAG, "Could not read the source track record: ${it.message}") }
            loaded.complete(Unit)
        }
        scope.launch { settingsRepository.rommUrl.collect { _rommBaseUrl.value = it } }
    }

    fun recordSuccess(origin: String?, bytes: Long, millis: Long) = record(origin, null, bytes, millis)

    /** The device's own trouble (no space, no folder access) is not held against the origin. */
    fun recordFailure(origin: String?, failure: DownloadFailure, bytes: Long, millis: Long) {
        if (SourceReliability.countsAgainstOrigin(failure)) record(origin, failure, bytes, millis)
    }

    private fun record(origin: String?, failure: DownloadFailure?, bytes: Long, millis: Long) {
        origin ?: return
        scope.launch {
            loaded.await()
            lock.withLock {
                val next = SourceReliability.withAttempt(_stats.value[origin], failure, bytes, millis, System.currentTimeMillis())
                _stats.update { it + (origin to next) }
                runCatching { dao.upsertSourceStat(SourceStatEntity.of(origin, next)) }
                    .onFailure { Log.w(TAG, "Could not save the record of $origin: ${it.message}") }
            }
        }
    }

    /** [items] best first by their origins' records; the order given when there is no history. */
    fun <T> orderBy(items: List<T>, originOf: (T) -> String?): List<T> =
        SourceReliability.order(items, originOf, _stats.value, System.currentTimeMillis())

    /** The origin a source address of Sources is recorded under. */
    fun originOfSource(url: String): String? = DownloadOrigins.forSourceUrl(url, _rommBaseUrl.value)
}

/**
 * The resume record of each unfinished web transfer (see [DownloadResumeEntity]): written when a
 * transfer starts from zero, read by every later attempt, dropped once the file is complete.
 */
@Singleton
class ResumeStore @Inject constructor(private val dao: DownloadTrackDao) {

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { dao.deleteResumesBefore(System.currentTimeMillis() - RESUME_KEEP_MS) }
        }
    }

    suspend fun stateFor(fileName: String): DownloadResumeEntity? = runCatching { dao.resumeFor(fileName) }.getOrNull()

    suspend fun started(fileName: String, validator: String?, origin: String?, totalBytes: Long?) {
        runCatching {
            dao.upsertResume(DownloadResumeEntity(fileName, validator, origin, totalBytes ?: -1L, System.currentTimeMillis()))
        }.onFailure { Log.w(TAG, "Could not save the resume record of $fileName: ${it.message}") }
    }

    suspend fun finished(fileName: String) {
        runCatching { dao.deleteResume(fileName) }
    }
}
