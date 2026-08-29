package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Starred games. Same shape as [com.cortinadev.dogmatix.data.service.LibraryIndexService.ownedKeys]. */
@Singleton
class FavouritesRepository @Inject constructor(
    private val dao: FavouriteDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** `consoleId|fileName` keys of every favourite. */
    val keys: StateFlow<Set<String>> = dao.observeAll()
        .map { list -> list.map { it.key }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isFavourite(file: DownloadableFileEntity, keys: Set<String> = this.keys.value): Boolean =
        FavouriteEntity.key(file.consoleId, file.fileName) in keys

    /** Star or un-star [file]; returns the new state. */
    suspend fun toggle(file: DownloadableFileEntity): Boolean {
        val now = isFavourite(file)
        if (now) dao.delete(file.consoleId, file.fileName)
        else dao.upsert(FavouriteEntity(file.consoleId, file.fileName))
        return !now
    }
}
