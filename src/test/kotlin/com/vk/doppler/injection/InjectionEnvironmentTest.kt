package com.vk.doppler.injection

import org.junit.Assert.*
import org.junit.Test

class InjectionEnvironmentTest {
    @Test fun `local values win without mutating either input`() {
        val local = mapOf("KEY" to "local", "ONLY_LOCAL" to "local")
        val secrets = mapOf("KEY" to "remote", "EMPTY" to "", "MULTILINE" to "one\ntwo")
        val merged = mergeInjectionEnvironment(local, secrets)
        assertEquals(local + mapOf("EMPTY" to "", "MULTILINE" to "one\ntwo"), merged)
        assertEquals("remote", secrets["KEY"])
        assertFalse(local.containsKey("EMPTY"))
        (merged as MutableMap).clear()
        assertEquals(2, local.size)
        assertEquals(3, secrets.size)
    }
}
