package com.vk.doppler.testutil

import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertFalse
import java.util.concurrent.TimeUnit

/** Pumps IDE events while joining a worker, failing with [message] if [timeoutMillis] expires. */
internal fun joinWithEventDispatch(
    thread: Thread,
    message: String,
    timeoutMillis: Long = 15_000,
) {
    require(timeoutMillis >= 0) { "Thread join timeout must not be negative." }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (thread.isAlive && System.nanoTime() < deadline) {
        UIUtil.dispatchAllInvocationEvents()
        thread.join(10)
    }
    assertFalse(message, thread.isAlive)
}
