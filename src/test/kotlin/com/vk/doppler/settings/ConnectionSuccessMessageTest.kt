package com.vk.doppler.settings

import com.vk.doppler.cli.DopplerAccountInfo
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport
import org.junit.Assert.*
import org.junit.Test

class ConnectionSuccessMessageTest {
    @Test
    fun `connection result shows identity slug type workplace and version`() {
        val account = DopplerAccountInfo("fingerprint", "1e866244-6ff7-47bf-8566-48a8849e27f0", "personal", "team", "Example Team", "VK-MacBook-Air-M4-13")
        val text = connectionSuccessMessage(DopplerStatusReport(DopplerStatus.READY, "v1", account.authContextId, account))
        assertTrue(text.contains("Identity: VK-MacBook-Air-M4-13 (1e866244-6ff7-47bf-8566-48a8849e27f0)"))
        assertTrue(text.contains("Identity type: personal"))
        assertFalse(text.contains("Account:"))
        assertTrue(text.contains("Workplace: Example Team (team)"))
        assertTrue(text.contains("CLI version: v1"))
        assertFalse(text.contains("fingerprint"))
    }

    @Test
    fun `identity without name falls back to slug`() {
        for (name in listOf(null, "", " ")) {
            val account = DopplerAccountInfo("hash", "identity-slug", "service", "team", null, name)
            val text = connectionSuccessMessage(DopplerStatusReport(DopplerStatus.READY, null, account = account))
            assertTrue(text.contains("Identity: identity-slug<br>"))
        }
    }

    @Test
    fun `missing workplace name falls back to slug and remote markup is escaped`() {
        val account = DopplerAccountInfo("hash", "<img src=x>", "service", "a&b", null, "<name>")
        val text = connectionSuccessMessage(DopplerStatusReport(DopplerStatus.READY, "<version>", account = account))
        assertFalse(text.contains("<img"))
        assertTrue(text.contains("&lt;img src=x&gt;"))
        assertTrue(text.contains("Identity: &lt;name&gt; (&lt;img src=x&gt;)"))
        assertTrue(text.contains("Workplace: a&amp;b"))
        assertTrue(text.contains("CLI version: &lt;version&gt;"))
    }
}
