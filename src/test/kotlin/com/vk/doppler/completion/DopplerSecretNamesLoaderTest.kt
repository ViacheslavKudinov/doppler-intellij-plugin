package com.vk.doppler.completion

import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.cli.DopplerConfig
import com.vk.doppler.cli.DopplerProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DopplerSecretNamesLoaderTest {
    private class FakeApi : DopplerNamesApi {
        var projects: List<DopplerProject> = emptyList()
        var configs: Map<String, List<DopplerConfig>> = emptyMap()
        var names: Map<Pair<String, String>, Set<String>> = emptyMap()
        var configFailures: Map<String, DopplerCliException> = emptyMap()
        var secretFailures: Map<Pair<String, String>, DopplerCliException> = emptyMap()
        var authContexts: ArrayDeque<String> = ArrayDeque(listOf("ctx"))
        var listedProjectsFor: String? = null
        val secretReads = AtomicInteger()
        val readOrder = ConcurrentLinkedQueue<Pair<String, String>>()
        val requestedAuthContexts = ConcurrentLinkedQueue<String>()

        override fun listProjects(authContextId: String): List<DopplerProject> {
            listedProjectsFor = authContextId
            return projects
        }

        override fun listConfigs(
            authContextId: String,
            projectSlug: String,
        ): List<DopplerConfig> {
            requestedAuthContexts += authContextId
            configFailures[projectSlug]?.let { throw it }
            return configs[projectSlug].orEmpty()
        }

        override fun listSecretNames(
            authContextId: String,
            projectSlug: String,
            configName: String,
            environment: String,
        ): Set<String> {
            requestedAuthContexts += authContextId
            secretReads.incrementAndGet()
            readOrder += projectSlug to configName
            secretFailures[projectSlug to configName]?.let { throw it }
            return names[projectSlug to configName].orEmpty()
        }

        override fun currentAuthContextId(): String = authContexts.first()
    }

    private val api = FakeApi()
    private val pool = Executors.newFixedThreadPool(3)
    private val loader = DopplerSecretNamesLoader(api, pool)

    @After
    fun shutDown() {
        pool.shutdownNow()
    }

    private fun project(slug: String) = DopplerProject(slug, slug, null)

    private fun config(name: String) = DopplerConfig(name, name)

    private fun generic(message: String = "boom") = DopplerCliException(message)

    private fun populate() {
        api.projects = listOf(project("app"), project("other"))
        api.configs =
            mapOf(
                "app" to listOf(config("api"), config("worker")),
                "other" to listOf(config("backend")),
            )
        api.names =
            mapOf(
                ("app" to "api") to setOf("A", "B"),
                ("app" to "worker") to setOf("QUEUE"),
                ("other" to "backend") to setOf("DATABASE_URL"),
            )
    }

    @Test
    fun `collects names for every project and config`() {
        populate()

        val names = loader.load("ctx", {}, {}, emptyMap())

        assertEquals(
            mapOf(
                "app" to mapOf("api" to setOf("A", "B"), "worker" to setOf("QUEUE")),
                "other" to mapOf("backend" to setOf("DATABASE_URL")),
            ),
            names,
        )
    }

    @Test
    fun `reports progress while loading projects and configs`() {
        populate()
        val progress = ConcurrentLinkedQueue<String>()

        loader.load("ctx", {}, progress::add, emptyMap())

        assertTrue(progress.contains("Listing Doppler projects…"))
        assertTrue(progress.contains("Listing configs for app…"))
        assertTrue(progress.contains("Loading secret names for app/api…"))
        assertTrue(progress.contains("Loading secret names for other/backend…"))
    }

    @Test
    fun `lists projects for the requested account`() {
        populate()

        loader.load("ctx", {}, {}, emptyMap())

        assertEquals("ctx", api.listedProjectsFor)
    }

    @Test
    fun `an empty account is an empty index`() {
        assertEquals(emptyMap<String, Map<String, Set<String>>>(), loader.load("ctx", {}, {}, emptyMap()))
    }

    @Test
    fun `a project without configs is kept as empty`() {
        api.projects = listOf(project("empty"))

        assertEquals(mapOf("empty" to emptyMap<String, Set<String>>()), loader.load("ctx", {}, {}, emptyMap()))
    }

    @Test
    fun `a config that cannot be read is skipped`() {
        populate()
        api.secretFailures = mapOf(("app" to "api") to generic("denied"))

        val names = loader.load("ctx", {}, {}, emptyMap())

        assertEquals(setOf("worker"), names.getValue("app").keys)
        assertEquals(setOf("backend"), names.getValue("other").keys)
    }

    @Test
    fun `a config read failure retains previously indexed names`() {
        populate()
        api.secretFailures = mapOf(("app" to "api") to generic("temporarily unavailable"))
        val previous = mapOf("app" to mapOf("api" to setOf("OLD"), "removed" to setOf("STALE")))

        val names = loader.load("ctx", {}, {}, previous)

        assertEquals(setOf("OLD"), names.getValue("app").getValue("api"))
        assertFalse(names.getValue("app").containsKey("removed"))
    }

    @Test
    fun `a project whose configs cannot be listed is skipped`() {
        populate()
        api.configFailures = mapOf("app" to generic("no access"))

        val names = loader.load("ctx", {}, {}, emptyMap())

        assertEquals(emptyMap<String, Set<String>>(), names.getValue("app"))
        assertEquals(mapOf("backend" to setOf("DATABASE_URL")), names.getValue("other"))
    }

    @Test
    fun `a project config-list failure retains its previously indexed names`() {
        populate()
        api.configFailures = mapOf("app" to generic("temporarily unavailable"))
        val previous = mapOf("app" to mapOf("api" to setOf("A"), "worker" to setOf("QUEUE")))

        val names = loader.load("ctx", {}, {}, previous)

        assertEquals(previous.getValue("app"), names.getValue("app"))
    }

    @Test
    fun `permission style failures do not abort the whole load`() {
        populate()
        api.secretFailures =
            mapOf(
                ("app" to "api") to DopplerCliException("forbidden", DopplerCliException.Kind.NOT_AUTHENTICATED),
            )

        assertEquals(setOf("worker"), loader.load("ctx", {}, {}, emptyMap()).getValue("app").keys)
    }

    @Test
    fun `a missing cli aborts the load`() {
        populate()
        api.secretFailures =
            mapOf(
                ("other" to "backend") to DopplerCliException("missing", DopplerCliException.Kind.CLI_NOT_FOUND),
            )

        try {
            loader.load("ctx", {}, {}, emptyMap())
            fail("Expected DopplerCliException")
        } catch (expected: DopplerCliException) {
            assertEquals(DopplerCliException.Kind.CLI_NOT_FOUND, expected.kind)
        }
    }

    @Test
    fun `an account change while reading aborts the load`() {
        populate()
        api.configFailures =
            mapOf(
                "other" to DopplerCliException("changed", DopplerCliException.Kind.AUTH_CONTEXT_CHANGED),
            )

        try {
            loader.load("ctx", {}, {}, emptyMap())
            fail("Expected DopplerCliException")
        } catch (expected: DopplerCliException) {
            assertEquals(DopplerCliException.Kind.AUTH_CONTEXT_CHANGED, expected.kind)
        }
    }

    @Test
    fun `each config and secret request carries the expected account context`() {
        populate()
        var checks = 0
        val counting =
            object : DopplerNamesApi by api {
                override fun currentAuthContextId(): String {
                    checks++
                    return "ctx"
                }
            }

        DopplerSecretNamesLoader(counting, pool).load("ctx", {}, {}, emptyMap())

        assertEquals(1, checks)
        assertEquals(3, api.secretReads.get())
        assertEquals(listOf("ctx", "ctx", "ctx", "ctx", "ctx"), api.requestedAuthContexts.sorted())
    }

    @Test
    fun `a different account at the end discards the result`() {
        populate()
        api.authContexts = ArrayDeque(listOf("someone-else"))

        try {
            loader.load("ctx", {}, {}, emptyMap())
            fail("Expected DopplerCliException")
        } catch (expected: DopplerCliException) {
            assertEquals(DopplerCliException.Kind.AUTH_CONTEXT_CHANGED, expected.kind)
        }
    }

    @Test
    fun `cancellation stops reading further configs`() {
        populate()
        val checks = AtomicInteger()

        try {
            loader.load("ctx", {
                if (checks.incrementAndGet() > 1) throw CancellationException()
            }, {}, emptyMap())
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(api.secretReads.get() < 3)
        }
    }

    @Test
    fun `cancellation is checked before every config`() {
        populate()
        val checks = AtomicInteger()

        loader.load("ctx", { checks.incrementAndGet() }, {}, emptyMap())

        // once per project plus once per config
        assertEquals(2 + 3, checks.get())
    }

    @Test
    fun `projects are read on the pool rather than the calling thread`() {
        populate()
        val caller = Thread.currentThread()
        val threads = ConcurrentLinkedQueue<Thread>()

        loader.load("ctx", { threads += Thread.currentThread() }, {}, emptyMap())

        assertTrue(threads.isNotEmpty())
        assertFalse(threads.any { it === caller })
    }

    @Test
    fun `secret values are never requested`() {
        populate()

        // The API surface only exposes names; reading values would need a different method.
        assertEquals(
            setOf("listProjects", "listConfigs", "listSecretNames", "currentAuthContextId"),
            DopplerNamesApi::class.java.methods
                .map { it.name }
                .toSet(),
        )
    }
}
