package com.vk.doppler.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.testutil.joinWithEventDispatch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    fun testRevokedLoginFromBackgroundClearsDirtySecretsInAWriteSafeContext() {
        val (service, file) = openConfig("background-revocation")
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: locally-edited") }
        val saved = observeWriteSafeSave(document)
        val failure = AtomicReference<Throwable>()
        val worker =
            Thread({
                service.pollOpenConfigs(
                    30_000,
                    0,
                    refreshableFiles = { emptyList() },
                    validateAccount = { throw DopplerCliException("Login revoked", DopplerCliException.Kind.NOT_AUTHENTICATED) },
                )
            }, "Doppler revoked-login test").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
                start()
            }
        joinWithEventDispatch(worker, "Revoked-login cleanup must finish without blocking the EDT")
        failure.get()?.let { throw AssertionError("Revoked-login cleanup failed", it) }
        assertTrue(saved())
        assertRevokedConfigCleared(file, document)
    }

    fun testRevokedLoginFromAnyModalityCallbackDefersDocumentWrites() {
        val (_, file) = openConfig("unsafe-edt-revocation")
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: locally-edited") }
        val saved = observeWriteSafeSave(document)
        val failure = AtomicReference<Throwable>()
        var callbackRan = false
        ApplicationManager.getApplication().invokeLater({
            try {
                assertFalse("Reproduce the unsafe callback from the crash", TransactionGuard.getInstance().isWritingAllowed)
                DopplerSecretsEditorService.invalidateCachedFiles(null, project)
                assertFalse("Unsafe callbacks must defer cleanup rather than write inline", file.isInvalidated)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                callbackRan = true
            }
        }, ModalityState.any())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while ((!callbackRan || !file.isInvalidated) && failure.get() == null && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        failure.get()?.let { throw AssertionError("Unsafe callback cleanup failed", it) }
        assertTrue(callbackRan)
        assertTrue(saved())
        assertRevokedConfigCleared(file, document)
    }

    private fun observeWriteSafeSave(document: Document): () -> Boolean {
        var saved = false
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(savedDocument: Document) {
                    if (savedDocument !== document) return
                    assertTrue(
                        "Cleared secret documents must be saved in a write-safe context",
                        TransactionGuard.getInstance().isWritingAllowed,
                    )
                    saved = true
                }
            },
        )
        return { saved }
    }

    private fun assertRevokedConfigCleared(
        file: DopplerSecretsFile,
        document: Document,
    ) {
        assertTrue(file.isInvalidated)
        assertFalse(file.isWritable)
        assertTrue(file.remoteSnapshot.isEmpty())
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, String(file.contentsToByteArray(), StandardCharsets.UTF_8))
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(file))
        assertNull(DopplerFileSystem.getInstance().findFileByPath(file.path))
        assertFalse(file.hasPendingSync)
        assertNull(file.pendingPushText.get())
    }

    fun testAnotherProjectsAccountRefreshKeepsThisProjectsSecretsOpen() {
        val (_, file) = openConfig()
        val document = myFixture.editor.document
        val text = document.text
        DopplerSecretsEditorService.invalidateCachedFiles(
            "another-project-account",
            ProjectManager.getInstance().defaultProject,
        )
        assertFalse(file.isInvalidated)
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
        assertEquals(text, document.text)
    }

    fun testAccountSwitchClearsDirtySelectedConfigWithoutFetchingValues() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: edited") }
        service.pollOpenConfigs(
            30_000,
            5,
            refreshableFiles = { service.autoRefreshFiles(true, arrayOf(file)) },
            refresh = {
                fail("Dirty config must not fetch values")
                false
            },
            validateAccount = { "new-account" },
        )
        assertTrue(file.isInvalidated)
        assertTrue(file.remoteSnapshot.isEmpty())
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
    }

    fun testLogoutClearsConfigWhenAnOrdinaryFileIsSelected() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        service.pollOpenConfigs(
            30_000,
            5,
            refreshableFiles = { service.autoRefreshFiles(true, arrayOf(LightVirtualFile("ordinary.txt"))) },
            refresh = {
                fail("Ordinary selection must not fetch values")
                false
            },
            validateAccount = { throw DopplerCliException("Logged out", DopplerCliException.Kind.NOT_AUTHENTICATED) },
        )
        assertTrue(file.isInvalidated)
        assertEquals(DopplerSecretsFile.EMPTY_DOCUMENT, document.text)
    }

    fun testInactiveWindowAndDisabledValueRefreshStillValidateAccountAtLimitedCadence() {
        val (service, file) = openConfig()
        val document = myFixture.editor.document
        WriteAction.run<RuntimeException> { document.setText("A: edited") }
        var checks = 0
        for (now in listOf(30_000L, 31_000L, 59_999L, 60_000L)) {
            service.pollOpenConfigs(
                now,
                0,
                refreshableFiles = { service.autoRefreshFiles(false, arrayOf(file)) },
                refresh = {
                    fail("Disabled refresh must not fetch values")
                    false
                },
                validateAccount = {
                    checks++
                    "old-account"
                },
            )
        }
        assertEquals(2, checks)
        assertFalse(file.isInvalidated)
        assertEquals("A: edited", document.text)
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
    }

    fun testValueRefreshSatisfiesAccountCheckUntilFallbackIntervalElapses() {
        val (service, file) = openConfig()
        var refreshes = 0
        var checks = 0
        service.pollOpenConfigs(
            30_000,
            5,
            refreshableFiles = { listOf(file) },
            refresh = {
                refreshes++
                true
            },
            validateAccount = {
                fail("Value refresh already checks authentication")
                "old-account"
            },
        )
        for (now in listOf(31_000L, 59_999L, 60_000L)) {
            service.pollOpenConfigs(
                now,
                5,
                refreshableFiles = { emptyList() },
                refresh = {
                    fail("No selected config")
                    false
                },
                validateAccount = {
                    checks++
                    "old-account"
                },
            )
        }
        assertEquals(1, refreshes)
        assertEquals(1, checks)
    }

    fun testFailedRefreshImmediatelyDetectsAccountSwitchInOtherOpenConfig() {
        checkFailedRefresh({ "new-account" }, true)
    }

    fun testFailedRefreshClearsOtherOpenConfigWhenAccountValidationFails() {
        checkFailedRefresh({ throw DopplerCliException("Account lookup failed") }, true)
    }

    fun testFailedRefreshPreservesOtherOpenConfigWhenAccountIsUnchanged() {
        checkFailedRefresh({ "old-account" }, false)
    }

    private fun checkFailedRefresh(
        validateAccount: () -> String,
        otherInvalidated: Boolean,
    ) {
        val (service, other) = openConfig("other")
        val document = FileDocumentManager.getInstance().getDocument(other)!!
        val (_, selected) = openConfig()
        service.pollOpenConfigs(
            30_000,
            5,
            refreshableFiles = { listOf(selected) },
            refresh = { true },
            validateAccount = {
                fail("Successful refresh already validates the account")
                "old-account"
            },
        )
        var checks = 0
        service.pollOpenConfigs(
            35_000,
            5,
            refreshableFiles = { listOf(selected) },
            refresh = { false },
            validateAccount = {
                checks++
                validateAccount()
            },
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

    fun testNoOpenConfigsMakesNoAccountOrValueRequests() {
        val (service, file) = openConfig()
        DopplerSecretsEditorService.invalidateCachedFiles(null)
        assertFalse(service.owns(file))
        service.pollOpenConfigs(
            30_000,
            5,
            refreshableFiles = {
                fail("No configs to refresh")
                emptyList()
            },
            refresh = {
                fail("No configs to refresh")
                false
            },
            validateAccount = {
                fail("No account check needed")
                "old-account"
            },
        )
    }
}
