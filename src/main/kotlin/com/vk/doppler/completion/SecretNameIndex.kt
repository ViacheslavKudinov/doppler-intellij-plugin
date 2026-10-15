package com.vk.doppler.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal fun interface SecretNamesLoader {
    /** Reads every config's secret names; reports progress and lets cancellation escape. */
    fun load(authContextId: String, checkCancelled: () -> Unit, reportProgress: (String) -> Unit): SecretNames
}

/**
 * In-memory index of secret names (never values) for the current Doppler account.
 *
 * Readers always get the last known names immediately; a missing or expired index is rebuilt in
 * the background, so typing is never blocked on the Doppler CLI.
 */
internal class SecretNameIndex(
    private val loader: SecretNamesLoader,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    private data class State(
        val authContextId: String? = null,
        val names: SecretNames = emptyMap(),
        val loadedAt: Long? = null,
        val retryAfter: Long = 0L,
        val loading: Boolean = false,
        val updatesDuringLoad: Map<Pair<String, String>, Set<String>> = emptyMap(),
    )

    private val lock = Any()
    private var state = State()
    private var onChanged: (() -> Unit)? = null

    @Volatile
    private var generation = 0

    /**
     * Returns the known names for [authContextId] and starts a reload when they are missing or
     * stale. [onChanged] runs once after a reload that changed the names; only the latest is kept.
     */
    fun namesFor(authContextId: String, onChanged: () -> Unit): SecretNames {
        var startedGeneration: Int? = null
        val names: SecretNames
        synchronized(lock) {
            if (state.authContextId != authContextId) {
                generation++
                state = State(authContextId = authContextId)
            }
            this.onChanged = onChanged
            val now = clock()
            val loadedAt = state.loadedAt
            val stale = loadedAt == null || now - loadedAt >= ttlMillis
            if (stale && !state.loading && now >= state.retryAfter) {
                state = state.copy(loading = true, updatesDuringLoad = emptyMap())
                startedGeneration = generation
            }
            names = state.names
        }
        startedGeneration?.let { start(authContextId, it) }
        return names
    }

    fun isLoading(): Boolean = synchronized(lock) { state.loading }

    /** Records fresh names for one config, e.g. after it was opened or saved. */
    fun update(authContextId: String, projectSlug: String, configName: String, names: Set<String>) {
        val copy = names.toSet()
        synchronized(lock) {
            if (state.authContextId != authContextId) return
            val updates = if (state.loading) {
                state.updatesDuringLoad + ((projectSlug to configName) to copy)
            } else {
                state.updatesDuringLoad
            }
            state = state.copy(names = withConfig(state.names, projectSlug, configName, copy), updatesDuringLoad = updates)
        }
    }

    /** Marks the names stale so the next read refreshes them, while still serving the old ones. */
    fun invalidate() {
        synchronized(lock) { state = state.copy(loadedAt = null, retryAfter = 0L) }
    }

    fun dispose() {
        synchronized(lock) {
            generation++
            state = State()
            onChanged = null
        }
    }

    private fun start(authContextId: String, startedGeneration: Int) {
        try {
            executor.execute { load(authContextId, startedGeneration) }
        } catch (_: RejectedExecutionException) {
            finish(startedGeneration, null, retry = true)
        }
    }

    private fun load(authContextId: String, startedGeneration: Int) {
        val indicator = ProgressManager.getInstance().progressIndicator
        val loaded = try {
            loader.load(
                authContextId,
                checkCancelled = {
                    indicator?.checkCanceled()
                    if (generation != startedGeneration) throw CancellationException()
                },
                reportProgress = { message -> indicator?.text2 = message },
            )
        } catch (e: ProcessCanceledException) {
            finish(startedGeneration, null, retry = false)
            throw e
        } catch (_: CancellationException) {
            finish(startedGeneration, null, retry = false)
            return
        } catch (e: Exception) {
            LOG.warn("Could not load Doppler secret names for completion: ${e.message}")
            finish(startedGeneration, null, retry = true)
            return
        }
        finish(startedGeneration, loaded, retry = false)
    }

    private fun finish(startedGeneration: Int, loaded: SecretNames?, retry: Boolean) {
        var notify: (() -> Unit)? = null
        synchronized(lock) {
            if (generation != startedGeneration) return
            val now = clock()
            state = if (loaded != null) {
                val merged = state.updatesDuringLoad.entries.fold(loaded) { names, (key, value) ->
                    withConfig(names, key.first, key.second, value)
                }
                if (merged != state.names) notify = onChanged
                onChanged = null
                state.copy(names = merged, loadedAt = now, retryAfter = 0L, loading = false, updatesDuringLoad = emptyMap())
            } else {
                state.copy(
                    loading = false,
                    retryAfter = if (retry) now + retryDelayMillis else state.retryAfter,
                    updatesDuringLoad = emptyMap(),
                )
            }
        }
        notify?.let {
            try {
                it()
            } catch (e: Exception) {
                LOG.warn("Secret reference listener failed", e)
            }
        }
    }

    private fun withConfig(names: SecretNames, project: String, config: String, secrets: Set<String>): SecretNames =
        names + (project to (names[project].orEmpty() + (config to secrets)))

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_RETRY_DELAY_MILLIS = 30 * 1000L
        private val LOG = Logger.getInstance(SecretNameIndex::class.java)
    }
}
