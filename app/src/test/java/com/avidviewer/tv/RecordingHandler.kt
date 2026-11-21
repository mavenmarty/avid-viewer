package com.avidviewer.tv

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Records uncaught coroutine exceptions (registered in META-INF/services), so a test can fail with where they came
 * from instead of a later Compose test failing with "uncaught exceptions before the test started". Nov 21, 2026.
 */
class RecordingHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) { seen.add(exception) }
    companion object { val seen = java.util.Collections.synchronizedList(ArrayList<Throwable>()) }
}
