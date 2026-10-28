package com.vk.doppler.editor

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.DopplerCliException
import com.vk.doppler.service.SecretChanges
import com.vk.doppler.service.applyChangesWithOptimisticLock

class DopplerSaveConflictTest : BasePlatformTestCase() {
    fun testFailureNotificationsNeverIncludeExceptionMessagesOrCauses() {
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Doppler") notifications.add(notification)
                }
            },
        )
        val marker = "synthetic-sensitive-error-detail"
        val recoveryMessages =
            mapOf(
                DopplerCliException.Kind.CLI_NOT_FOUND to "Check the Doppler CLI path in Settings.",
                DopplerCliException.Kind.NOT_AUTHENTICATED to "Log in with the Doppler CLI, then try again.",
                DopplerCliException.Kind.AUTH_CONTEXT_CHANGED to
                    "The Doppler account changed. Reopen this config under the current account.",
                DopplerCliException.Kind.REMOTE_CHANGED to
                    "This config changed in Doppler. Your edits were kept. Reload and reconcile before saving again.",
                DopplerCliException.Kind.GENERIC to "Check the Doppler CLI and try again.",
            )
        val failures =
            recoveryMessages.map { (kind, message) ->
                DopplerCliException(marker, kind, IllegalStateException(marker)) to message
            } + (IllegalStateException(marker, IllegalArgumentException(marker)) to "Check the Doppler CLI and try again.")

        for ((index, failure) in failures.withIndex()) {
            val baseline = mapOf("A" to "old")
            val edited = "A: mine"
            val config = "sanitized-failure-$index"
            val file = DopplerFileSystem.getInstance().obtain("conflict", config, "test-context")
            file.setServerContent(edited, baseline)
            myFixture.configureFromExistingVirtualFile(file)
            val service = own(file)
            file.hasPendingSync = true
            file.pushInProgress.set(true)
            val changes = SecretChanges.between(baseline, SecretsDocumentFormat.parse(edited))
            val task = service.createPushTask(file, edited, baseline, changes) {}
            val previousCount = notifications.size

            task.onThrowable(failure.first)
            UIUtil.dispatchAllInvocationEvents()

            assertEquals(previousCount + 1, notifications.size)
            val notification = notifications.last()
            assertEquals(NotificationType.ERROR, notification.type)
            assertEquals("Failed to sync /conflict/$config. ${failure.second}", notification.content)
            assertFalse(notification.title.contains(marker))
            assertFalse(notification.content.contains(marker))
            task.onFinished()
            notification.expire()
        }
    }

    // Model a config opened by the tool window, without invoking the real Doppler CLI.
    private fun own(file: DopplerSecretsFile): DopplerSecretsEditorService {
        val service = DopplerSecretsEditorService.getInstance(project)
        val field = DopplerSecretsEditorService::class.java.getDeclaredField("openPaths")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(service) as MutableSet<String>).add(file.path)
        return service
    }

    fun testSuccessfulSaveStillDrainsQueuedSaveAgainstTheCommittedBaseline() {
        val baseline = mapOf("A" to "old")
        val edited = "A: mine"
        val file = DopplerFileSystem.getInstance().obtain("conflict", "success", "test-context")
        file.setServerContent(edited, baseline)
        myFixture.configureFromExistingVirtualFile(file)
        val service = own(file)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        service.push(file, edited)
        val changes = SecretChanges.between(baseline, SecretsDocumentFormat.parse(edited))
        val task = service.createPushTask(file, edited, baseline, changes) {}
        task.run(EmptyProgressIndicator())
        task.onSuccess()
        assertTrue(file.hasPendingSync)
        task.onFinished()

        assertEquals(mapOf("A" to "mine"), file.remoteSnapshot)
        assertNull(file.pendingPushText.get())
        assertFalse(file.pushInProgress.get())
        // The queued identical save was processed and recognized as already synced.
        assertFalse(file.hasPendingSync)
    }

    fun testRemoteAdditionsModificationsAndDeletionsSurviveQueuedAndLaterSaves() {
        val baseline = mapOf("A" to "old", "B" to "old-b")
        val edited = "A: mine\nB: old-b"
        val remoteStates =
            listOf(
                baseline + ("TEAMMATE" to "new"),
                baseline + ("B" to "teammate"),
                baseline - "B",
            )
        for ((index, remote) in remoteStates.withIndex()) {
            val file = DopplerFileSystem.getInstance().obtain("conflict", "dev-$index", "test-context")
            file.setServerContent(edited, baseline)
            myFixture.configureFromExistingVirtualFile(file)
            val service = own(file)
            var writes = 0

            repeat(2) { attempt ->
                file.hasPendingSync = true
                file.pushInProgress.set(true)
                if (attempt == 0) service.push(file, edited)
                val changes = SecretChanges.between(file.remoteSnapshot, SecretsDocumentFormat.parse(edited))
                val task =
                    service.createPushTask(file, edited, file.remoteSnapshot, changes) {
                        applyChangesWithOptimisticLock(
                            file.remoteSnapshot,
                            changes,
                            {},
                            { remote },
                            { writes++ },
                            { _, _ -> writes++ },
                        )
                    }
                try {
                    task.run(EmptyProgressIndicator())
                    fail("Expected remote conflict")
                } catch (error: DopplerCliException) {
                    assertEquals(DopplerCliException.Kind.REMOTE_CHANGED, error.kind)
                    task.onThrowable(error)
                }
                task.onFinished()

                assertEquals(baseline, file.remoteSnapshot)
                assertEquals(edited, myFixture.editor.document.text)
                assertTrue(file.hasPendingSync)
                assertFalse(file.pushInProgress.get())
                assertNull(file.pendingPushText.get())
                assertEquals(0, writes)
            }
        }
    }

    fun testPartialFailurePreservesBaselineAndCancelsQueuedSave() {
        val baseline = mapOf("A" to "old", "B" to "old-b")
        val edited = "A: mine\nB: mine-b"
        val file = DopplerFileSystem.getInstance().obtain("conflict", "partial", "test-context")
        file.setServerContent(edited, baseline)
        myFixture.configureFromExistingVirtualFile(file)
        val service = own(file)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        service.push(file, edited)
        val remote = baseline.toMutableMap()
        val changes = SecretChanges.between(baseline, SecretsDocumentFormat.parse(edited))
        val task =
            service.createPushTask(file, edited, baseline, changes) {
                remote["A"] = "mine"
                throw DopplerCliException("Synthetic partial failure", DopplerCliException.Kind.GENERIC)
            }
        try {
            task.run(EmptyProgressIndicator())
            fail("Expected partial failure")
        } catch (error: DopplerCliException) {
            task.onThrowable(error)
        }
        task.onFinished()

        assertEquals(baseline, file.remoteSnapshot)
        assertEquals(edited, myFixture.editor.document.text)
        assertEquals(mapOf("A" to "mine", "B" to "old-b"), remote)
        assertTrue(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
        assertNull(file.pendingPushText.get())
    }

    fun testCorrectedEditSavesAfterAConfirmedPartialWriteWithoutDiscardingLocalEdits() {
        val baseline = mapOf("A" to "old", "B" to "old-b")
        val edited = "A: mine\nB: rejected"
        val file = DopplerFileSystem.getInstance().obtain("conflict", "confirmed-partial", "test-context")
        file.setServerContent(edited, baseline)
        myFixture.configureFromExistingVirtualFile(file)
        val service = own(file)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        val remote = baseline.toMutableMap()
        val changes = SecretChanges.between(baseline, SecretsDocumentFormat.parse(edited))
        val failedTask =
            service.createPushTask(file, edited, baseline, changes) {
                applyChangesWithOptimisticLock(
                    baseline,
                    changes,
                    {},
                    { remote.toMap() },
                    {},
                    { name, value ->
                        if (name == "B") throw DopplerCliException("Synthetic rejected edit")
                        remote[name] = value
                    },
                    onSnapshotAdvanced = { file.remoteSnapshot = it },
                )
            }
        try {
            failedTask.run(EmptyProgressIndicator())
            fail("Expected rejected edit")
        } catch (error: DopplerCliException) {
            failedTask.onThrowable(error)
        }
        failedTask.onFinished()
        assertEquals(remote, file.remoteSnapshot)
        assertEquals(edited, myFixture.editor.document.text)
        assertTrue(file.hasPendingSync)

        val corrected = "A: mine\nB: corrected"
        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            myFixture.editor.document.setText(corrected)
        }
        val expected = file.remoteSnapshot
        val remaining = SecretChanges.between(expected, SecretsDocumentFormat.parse(corrected))
        assertEquals(mapOf("B" to "corrected"), remaining.upserted)
        file.pushInProgress.set(true)
        val retry =
            service.createPushTask(file, corrected, expected, remaining) {
                applyChangesWithOptimisticLock(
                    expected,
                    remaining,
                    {},
                    { remote.toMap() },
                    {},
                    { name, value -> remote[name] = value },
                    onSnapshotAdvanced = { file.remoteSnapshot = it },
                )
            }
        retry.run(EmptyProgressIndicator())
        retry.onSuccess()
        retry.onFinished()
        assertEquals(mapOf("A" to "mine", "B" to "corrected"), remote)
        assertEquals(remote, file.remoteSnapshot)
    }
}
