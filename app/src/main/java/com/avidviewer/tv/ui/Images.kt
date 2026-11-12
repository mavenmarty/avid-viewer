package com.avidviewer.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Posters, logos and badges (DATA-04). While a remote button is held, rows fly past faster than anyone can look
 * at them; a picture only starts loading once the button is let go and it is still on screen, instead of one
 * request for every row that went by (230 requests in 10 s, most of them cancelled). All pictures go through
 * one image loader with a capped number of downloads at a time (see [imageLoader]).
 */
object Images {
    /** True while a button is held down (its key repeats). Set by the app's key handling. */
    @Volatile var holding = false

    /** The app's one image loader: at most 6 downloads at a time (3 per server), a memory and a disk cache. */
    fun imageLoader(context: android.content.Context): coil.ImageLoader =
        coil.ImageLoader.Builder(context)
            .okHttpClient {
                com.avidviewer.tv.data.Net.client.newBuilder()
                    .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 3 })
                    .build()
            }
            .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.15).build() }
            .diskCache { coil.disk.DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(64L * 1024 * 1024).build() }
            .crossfade(false)
            .build()
}

@Composable
fun TvImage(model: Any?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    var go by remember(model) { mutableStateOf(!Images.holding) }
    if (!go) {
        LaunchedEffect(model) {
            while (Images.holding) delay(60)
            delay(80)
            go = true
        }
        return
    }
    AsyncImage(model = model, contentDescription = contentDescription, contentScale = contentScale, modifier = modifier)
}
