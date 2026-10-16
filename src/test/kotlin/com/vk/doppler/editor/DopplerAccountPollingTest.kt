package com.vk.doppler.editor

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.cli.DopplerCliException
import java.util.concurrent.ScheduledFuture

class DopplerAccountPollingTest : BasePlatformTestCase() {
    private fun openConfig(configName: String = "dev"): Pair<DopplerSecretsEditorService, DopplerSecretsFile> {
        val service = DopplerSecretsEditorService.getInstance(project)
        // Drive the polling clock explicitly; never invoke a real CLI from these tests.
        val timer = DopplerSecretsEditorService::class.java.getDeclaredField("pollHandle")
        timer.isAccessible = true
        (timer.get(service) as ScheduledFuture<*>).cancel(false)
        for (name in listOf("lastPollAt", "lastAccountValidationAt")) {
            val clock = DopplerSecretsEditorService::class.java.getDeclaredField(name)
            clock.isAccessible = true
            clock.setLong(service, 0L)
        }
        val file = DopplerFileSystem.getInstance().obtain("polling", configName, "old-account")
        file.setServerContent(SecretsDocumentFormat.serialize(mapOf("A" to "old")), mapOf("A" to "old"))
        myFixture.configureFromExistingVirtualFile(file)
        val paths = DopplerSecretsEditorService::class.java.getDeclaredField("openPaths")
        paths.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (paths.get(service) as MutableSet<String>).add(file.path)
        return service to file
    }

    fun `test account switch clears dirty selected config without fetching values`() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: edited") }
        service.pollOpenConfigs(30_000, 5,
            refreshableFiles = { service.autoRefreshFiles(true, arrayOf(file)) },
            refresh = { fail("Dirty config must not fetch values"); false },
            validateAccount = { "new-account" },
        )
        assertTrue(file.isInvalidated)
        assertTrue(file.remoteSnapshot.isEmpty())
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
    }

    fun `test logout clears config when an ordinary file is selected`() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        service.pollOpenConfigs(30_000, 5,
            refreshableFiles = { service.autoRefreshFiles(true, arrayOf(LightVirtualFile("ordinary.txt"))) },
            refresh = { fail("Ordinary selection must not fetch values"); false },
            validateAccount = { throw DopplerCliException("Logged out", DopplerCliException.Kind.NOT_AUTHENTICATED) },
        )
        assertTrue(file.isInvalidated)
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
    }

    fun `test inactive window and disabled value refresh still validate account at limited cadence`() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: edited") }
        var checks = 0
        for (now in listOf(30_000L, 31_000L, 59_999L, 60_000L)) {
            service.pollOpenConfigs(now, 0,
                refreshableFiles = { service.autoRefreshFiles(false, arrayOf(file)) },
                refresh = { fail("Disabled refresh must not fetch values"); false },
                validateAccount = { checks++; "old-account" },
            )
        }
        assertEquals(2, checks)
        assertFalse(file.isInvalidated)
        assertEquals("A: edited", document.text)
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
    }

    fun `test value refresh satisfies account check until fallback interval elapses`() {
        val (service, file) = openConfig()
        var refreshes = 0
        var checks = 0
        service.pollOpenConfigs(30_000, 5,
            refreshableFiles = { listOf(file) },
            refresh = { refreshes++; true },
            validateAccount = { fail("Value refresh already checks authentication"); "old-account" },
        )
        for (now in listOf(31_000L, 59_999L, 60_000L)) {
            service.pollOpenConfigs(now, 5,
                refreshableFiles = { emptyList() },
                refresh = { fail("No selected config"); false },
                validateAccount = { checks++; "old-account" },
            )
        }
        assertEquals(1, refreshes)
        assertEquals(1, checks)
    }

    fun `test failed refresh immediately detects account switch in other open config`() {
        checkFailedRefresh({ "new-account" }, true)
    }

    fun `test failed refresh clears other open config when account validation fails`() {
        checkFailedRefresh({ throw DopplerCliException("Account lookup failed") }, true)
    }

    fun `test failed refresh preserves other open config when account is unchanged`() {
        checkFailedRefresh({ "old-account" }, false)
    }

    private fun checkFailedRefresh(validateAccount: () -> String, otherInvalidated: Boolean) {
        val (service, other) = openConfig("other")
        val document = FileDocumentManager.getInstance().getDocument(other)!!
        val (_, selected) = openConfig()
        service.pollOpenConfigs(30_000, 5,
            refreshableFiles = { listOf(selected) },
            refresh = { true },
            validateAccount = { fail("Successful refresh already validates the account"); "old-account" },
        )
        var checks = 0
        service.pollOpenConfigs(35_000, 5,
            refreshableFiles = { listOf(selected) },
            refresh = { false },
            validateAccount = { checks++; validateAccount() },
        )
        assertEquals(1, checks)
        assertEquals(otherInvalidated, other.isInvalidated)
        if (otherInvalidated) {
            assertTrue(other.remoteSnapshot.isEmpty())
            assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
        } else {
            assertEquals(mapOf("A" to "old"), other.remoteSnapshot)
            assertEquals(SecretsDocumentFormat.serialize(other.remoteSnapshot), document.text)
        }
    }

    fun `test no open configs makes no account or value requests`() {
        val (service, file) = openConfig()
        DopplerSecretsEditorService.invalidateCachedFiles(null)
        assertFalse(service.owns(file))
        service.pollOpenConfigs(30_000, 5,
            refreshableFiles = { fail("No configs to refresh"); emptyList() },
            refresh = { fail("No configs to refresh"); false },
            validateAccount = { fail("No account check needed"); "old-account" },
        )
    }
}
