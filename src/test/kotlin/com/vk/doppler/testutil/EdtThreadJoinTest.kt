package com.vk.doppler.testutil

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class EdtThreadJoinTest : BasePlatformTestCase() {
    fun testJoiningOnEdtDispatchesCallbacksNeededByTheWorker() {
        assertTrue(SwingUtilities.isEventDispatchThread())
        val callbackRan = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val thread =
            Thread({
                SwingUtilities.invokeAndWait { callbackRan.set(true) }
            }, "EDT-dependent test worker").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
                start()
            }
        try {
            joinWithEventDispatch(thread, "Worker should finish after its EDT callback")
            assertFalse(thread.isAlive)
            assertTrue(callbackRan.get())
            assertNull(failure.get())
        } finally {
            UIUtil.dispatchAllInvocationEvents()
            thread.join(1_000)
            assertFalse("Test worker should be cleaned up", thread.isAlive)
        }
    }

    fun testAlreadyCompletedWorkerNeedsNoWaiting() {
        val thread =
            Thread("Completed test worker").apply {
                start()
                join(1_000)
            }
        assertFalse(thread.isAlive)
        joinWithEventDispatch(thread, "Completed worker should not time out", timeoutMillis = 0)
    }

    fun testTimeoutReportsTheCallerMessageWithoutStoppingTheWorker() {
        val release = CountDownLatch(1)
        val thread = Thread({ release.await() }, "Blocked test worker").apply { start() }
        try {
            try {
                joinWithEventDispatch(thread, "Expected worker timeout", timeoutMillis = 0)
                fail("A live worker must fail when the timeout expires")
            } catch (expected: AssertionError) {
                assertEquals("Expected worker timeout", expected.message)
            }
            assertTrue(thread.isAlive)
        } finally {
            release.countDown()
            thread.join(1_000)
            assertFalse("Test worker should be cleaned up", thread.isAlive)
        }
    }

    fun testNegativeTimeoutIsRejected() {
        try {
            joinWithEventDispatch(Thread(), "Unused assertion", timeoutMillis = -1)
            fail("Negative timeouts must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Thread join timeout must not be negative.", expected.message)
        }
    }
}
