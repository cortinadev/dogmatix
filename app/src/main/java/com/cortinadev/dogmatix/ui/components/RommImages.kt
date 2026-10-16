package com.cortinadev.dogmatix.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.cortinadev.dogmatix.data.service.RommCatalogService
import com.cortinadev.dogmatix.data.service.RommImageAuth
import com.cortinadev.dogmatix.di.RommEntryPoint
import dagger.hilt.android.EntryPointAccessors

@Composable
fun rememberRommCatalog(): RommCatalogService {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, RommEntryPoint::class.java).rommCatalogService() }
}

/**
 * The Coil model of an image [url]: a request carrying the RomM `Authorization` header when the
 * URL is on the RomM server, the URL itself otherwise (no other host ever sees the token).
 */
@Composable
fun rememberImageModel(url: String?): Any? {
    val auth by rememberRommCatalog().imageAuth.collectAsState()
    val context = LocalContext.current
    return remember(url, auth) { RommImages.model(context, url, auth) }
}

object RommImages {
    fun model(context: Context, url: String?, auth: RommImageAuth?): Any? = when {
        url == null -> null
        auth != null && auth.covers(url) -> ImageRequest.Builder(context).data(url).addHeader("Authorization", auth.header).build()
        else -> url
    }
}
