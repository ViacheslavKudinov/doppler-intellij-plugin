package com.vk.doppler.completion

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class SecretNameIndexTest {

    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        val pending: Int get() = queue.size

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class FakeLoader : SecretNamesLoader {
        var namesByContext: Map<String, SecretNames> = emptyMap()
        var failure: Exception? = null
        var calls = 0
        val cancelledContexts = mutableListOf<String>()

        override fun load(
            authContextId: String,
            checkCancelled: () -> Unit,
            reportProgress: (String) -> Unit,
        ): SecretNames {
            calls++
            failure?.let { throw it }
            try {
                checkCancelled()
            } catch (e: CancellationException) {
                cancelledContexts += authContextId
                throw e
            }
            return namesByContext.getValue(authContextId)
        }
    }

    private val loader = FakeLoader()
    private val executor = ManualExecutor()
    private var now = 1_000L

    private val first: SecretNames = mapOf("app" to mapOf("api" to setOf("A")))
    private val second: SecretNames = mapOf("app" to mapOf("api" to setOf("A", "B")))

    private fun index(ttl: Long = 1_000L, retry: Long = 500L) =
        SecretNameIndex(loader, executor, { now }, ttl, retry)

    @Test
    fun `the first read returns nothing and loads in the background`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()

        assertEquals(emptyMap<String, Map<String, Set<String>>>(), index.namesFor("ctx") {})
        assertEquals(1, executor.pending)
        assertTrue(index.isLoading())
        assertEquals(0, loader.calls)

        executor.runAll()

        assertFalse(index.isLoading())
        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `reads while a load is running do not start another`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()

        repeat(5) { index.namesFor("ctx") {} }

        assertEquals(1, executor.pending)
        executor.runAll()
        assertEquals(1, loader.calls)
    }

    @Test
    fun `fresh names are served from memory`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        now += 999
        index.namesFor("ctx") {}

        assertEquals(0, executor.pending)
        assertEquals(1, loader.calls)
    }

    @Test
    fun `stale names are served while they reload`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        now += 1_000
        loader.namesByContext = mapOf("ctx" to second)
        val served = index.namesFor("ctx") {}

        assertEquals(first, served)
        assertEquals(1, executor.pending)
        executor.runAll()
        assertEquals(second, index.namesFor("ctx") {})
        assertEquals(2, loader.calls)
    }

    @Test
    fun `a failed load is retried only after the delay`() {
        val index = index(retry = 500L)
        loader.failure = IllegalStateException("cli down")
        index.namesFor("ctx") {}
        executor.runAll()
        assertEquals(1, loader.calls)

        now += 499
        index.namesFor("ctx") {}
        assertEquals(0, executor.pending)

        now += 1
        loader.failure = null
        loader.namesByContext = mapOf("ctx" to first)
        index.namesFor("ctx") {}
        executor.runAll()

        assertEquals(2, loader.calls)
        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `a cancelled load can be retried immediately`() {
        val index = index()
        loader.failure = ProcessCanceledException()
        index.namesFor("ctx") {}

        try {
            executor.runAll()
            fail("Expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // Cancellation should leave the index ready to try again.
        }

        loader.failure = null
        loader.namesByContext = mapOf("ctx" to first)
        index.namesFor("ctx") {}

        assertEquals(1, executor.pending)
        executor.runAll()
        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `a failed load keeps the previously known names`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        now += 1_000
        loader.failure = IllegalStateException("boom")
        index.namesFor("ctx") {}
        executor.runAll()

        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `the listener runs once after names change`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        var notified = 0

        index.namesFor("ctx") { notified++ }
        assertEquals(0, notified)
        executor.runAll()

        assertEquals(1, notified)
    }

    @Test
    fun `the listener is skipped when a reload finds nothing new`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        now += 1_000
        var notified = 0
        index.namesFor("ctx") { notified++ }
        executor.runAll()

        assertEquals(0, notified)
    }

    @Test
    fun `the listener runs when a reload brings new names`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        now += 1_000
        loader.namesByContext = mapOf("ctx" to second)
        var notified = 0
        index.namesFor("ctx") { notified++ }
        executor.runAll()

        assertEquals(1, notified)
    }

    @Test
    fun `only the latest listener is kept`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        var older = 0
        var latest = 0

        index.namesFor("ctx") { older++ }
        index.namesFor("ctx") { latest++ }
        executor.runAll()

        assertEquals(0, older)
        assertEquals(1, latest)
    }

    @Test
    fun `a throwing listener does not break the index`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()

        index.namesFor("ctx") { throw IllegalStateException("listener") }
        executor.runAll()

        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `switching account drops the old names and reloads`() {
        loader.namesByContext = mapOf("a" to first, "b" to second)
        val index = index()
        index.namesFor("a") {}
        executor.runAll()

        assertEquals(emptyMap<String, Map<String, Set<String>>>(), index.namesFor("b") {})
        executor.runAll()

        assertEquals(second, index.namesFor("b") {})
    }

    @Test
    fun `a late result for the previous account is ignored`() {
        loader.namesByContext = mapOf("a" to first, "b" to second)
        val index = index()

        index.namesFor("a") {}
        index.namesFor("b") {}
        assertEquals(2, executor.pending)
        executor.runAll()

        assertEquals(listOf("a"), loader.cancelledContexts)
        assertEquals(second, index.namesFor("b") {})
    }

    @Test
    fun `updates are applied to known names`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        index.update("ctx", "app", "worker", setOf("QUEUE"))

        assertEquals(
            mapOf("app" to mapOf("api" to setOf("A"), "worker" to setOf("QUEUE"))),
            index.namesFor("ctx") {},
        )
    }

    @Test
    fun `an update replaces the names of its config`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        index.update("ctx", "app", "api", setOf("NEW"))

        assertEquals(mapOf("app" to mapOf("api" to setOf("NEW"))), index.namesFor("ctx") {})
    }

    @Test
    fun `an update for another account is ignored`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        index.update("someone-else", "app", "api", setOf("LEAK"))

        assertEquals(first, index.namesFor("ctx") {})
    }

    @Test
    fun `an update before the index was ever read is ignored`() {
        val index = index()

        index.update("ctx", "app", "api", setOf("A"))

        assertEquals(emptyMap<String, Map<String, Set<String>>>(), index.namesFor("ctx") {})
    }

    @Test
    fun `an update made while loading survives the load`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}

        index.update("ctx", "app", "worker", setOf("QUEUE"))
        executor.runAll()

        assertEquals(
            mapOf("app" to mapOf("api" to setOf("A"), "worker" to setOf("QUEUE"))),
            index.namesFor("ctx") {},
        )
    }

    @Test
    fun `the index keeps its own copy of updated names`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()
        val mutable = mutableSetOf("ONE")

        index.update("ctx", "app", "worker", mutable)
        mutable += "TWO"

        assertEquals(setOf("ONE"), index.namesFor("ctx") {}.getValue("app").getValue("worker"))
    }

    @Test
    fun `invalidate refreshes on the next read while serving the old names`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        index.namesFor("ctx") {}
        executor.runAll()

        index.invalidate()
        loader.namesByContext = mapOf("ctx" to second)

        assertEquals(first, index.namesFor("ctx") {})
        assertEquals(1, executor.pending)
        executor.runAll()
        assertEquals(second, index.namesFor("ctx") {})
    }

    @Test
    fun `invalidate also lifts a retry delay`() {
        val index = index(retry = 10_000L)
        loader.failure = IllegalStateException("boom")
        index.namesFor("ctx") {}
        executor.runAll()

        loader.failure = null
        loader.namesByContext = mapOf("ctx" to first)
        index.invalidate()
        index.namesFor("ctx") {}

        assertEquals(1, executor.pending)
    }

    @Test
    fun `dispose forgets everything and drops a running load`() {
        loader.namesByContext = mapOf("ctx" to first)
        val index = index()
        var notified = 0
        index.namesFor("ctx") { notified++ }

        index.dispose()
        executor.runAll()

        assertEquals(0, notified)
        assertEquals(listOf("ctx"), loader.cancelledContexts)
        assertEquals(emptyMap<String, Map<String, Set<String>>>(), index.namesFor("ctx") {})
    }

    @Test
    fun `a rejected executor does not break reads and is retried later`() {
        var attempts = 0
        val rejecting = Executor {
            attempts++
            throw RejectedExecutionException("shutdown")
        }
        val index = SecretNameIndex(loader, rejecting, { now }, 1_000L, 500L)

        assertEquals(emptyMap<String, Map<String, Set<String>>>(), index.namesFor("ctx") {})
        index.namesFor("ctx") {}
        assertEquals(1, attempts)

        now += 500
        assertTrue(index.namesFor("ctx") {}.isEmpty())
        assertEquals(2, attempts)
        assertEquals(0, loader.calls)
    }
}
