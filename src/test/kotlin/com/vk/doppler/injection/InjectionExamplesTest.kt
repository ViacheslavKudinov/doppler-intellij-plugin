package com.vk.doppler.injection

import junit.framework.TestCase
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class InjectionExamplesTest : TestCase() {
    fun testJavaExampleModesAndFailures() {
        val plain = mapOf("EXAMPLE_MESSAGE" to "doppler-example", "EXAMPLE_NUMBER" to "42")
        val custom = mapOf("APP_MESSAGE" to "doppler-example", "APP_NUMBER" to "42")
        assertExample(emptyList(), plain, 0)
        assertExample(listOf("plain"), plain, 0)
        assertExample(listOf("custom"), custom, 0)
        assertExample(listOf("plain"), emptyMap(), 1)
        assertExample(listOf("plain"), plain + ("EXAMPLE_NUMBER" to "unexpected-test-marker"), 1)
        assertExample(listOf("custom"), custom + ("EXAMPLE_MESSAGE" to ""), 1)
        assertExample(listOf("custom"), custom + ("EXAMPLE_NUMBER" to ""), 1)
        assertExample(listOf("invalid"), plain, 2)
        assertExample(listOf("plain", "extra"), plain, 2)
    }

    private fun assertExample(arguments: List<String>, variables: Map<String, String>, expectedExit: Int) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val source = Path.of("examples", "java-maven", "src", "main", "java", "com", "vk", "doppler", "examples", "InjectionExample.java")
            .toAbsolutePath().toString()
        val builder = ProcessBuilder(listOf(java, source) + arguments).redirectErrorStream(true)
        builder.environment().apply {
            listOf("EXAMPLE_MESSAGE", "EXAMPLE_NUMBER", "APP_MESSAGE", "APP_NUMBER").forEach { remove(it) }
            putAll(variables)
        }
        val process = builder.start()
        try {
            assertTrue("Example timed out", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(expectedExit, process.exitValue())
            assertTrue(output.trim().startsWith(if (expectedExit == 0) "PASS:" else "FAIL:"))
            assertFalse(output.contains("unexpected-test-marker"))
        } finally {
            process.destroyForcibly()
        }
    }
}