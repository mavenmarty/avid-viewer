package com.avidviewer.tv

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** ci-check only: records uncaught coroutine exceptions so a test can show where they came from. */
class RecordingHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) { seen.add(exception) }
    companion object { val seen = java.util.Collections.synchronizedList(ArrayList<Throwable>()) }
}
