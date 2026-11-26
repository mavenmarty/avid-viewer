package com.avidviewer.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nov 26, 2026 (owner: a search took 3 minutes): database reads that stop when the coroutine waiting for them is
 * cancelled. Typing with the remote starts a new search at each pause ("mo", "mot", "moto", "motor"); each older
 * search was cancelled, but a database query can't be interrupted by that, so they ran to the end one after another,
 * ahead of the one wanted. Queries run inside [run] are given a [android.os.CancellationSignal] and stop at once.
 */
object QueryCancel {
    private val current = ThreadLocal<android.os.CancellationSignal?>()

    /** The signal for queries made inside [run] on this thread (null elsewhere: those run to the end as before). */
    fun signal(): android.os.CancellationSignal? = current.get()

    suspend fun <T> run(block: () -> T): T = coroutineScope {
        val s = android.os.CancellationSignal()
        val watcher = launch(Dispatchers.Default) { try { awaitCancellation() } finally { s.cancel() } }
        try {
            withContext(Dispatchers.IO) { current.set(s); try { block() } finally { current.set(null) } }
        } catch (e: android.os.OperationCanceledException) {
            throw CancellationException("replaced by a newer search")
        } finally { watcher.cancel() }
    }
}
