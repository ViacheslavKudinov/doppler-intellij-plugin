package com.vk.doppler.editor

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tabs.TabInfo
import com.vk.doppler.DopplerIcons
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.settings.DopplerFolderSetupState
import java.nio.charset.StandardCharsets
import javax.swing.JPanel

/**
 * Guards the core assumption behind the two-way sync: an ordinary editor save must reach
 * [DopplerSecretsFile.getOutputStream], which is where the push to Doppler is triggered.
 *
 * A `LightVirtualFile` silently fails this, because the platform never marks documents in a
 * non-physical file system as unsaved.
 */
class DopplerSecretsFileSaveTest : BasePlatformTestCase() {
    private fun newFile(
        configName: String,
        text: String,
        snapshot: Map<String, String?>,
    ): DopplerSecretsFile =
        DopplerFileSystem.getInstance().obtain("backend", configName, "test-context").also {
            it.setServerContent(text, snapshot)
        }

    fun testTheFileSystemIsRegisteredUnderTheDopplerProtocol() {
        assertNotNull(DopplerFileSystem.getInstance())
    }

    fun testPresentableUrlsHideAuthContextAndFollowEnvironmentMetadataOutsideTabs() {
        val fs = DopplerFileSystem.getInstance()
        val file = fs.obtain("presentable", "dev", "synthetic-auth-fingerprint")
        try {
            assertEquals("/presentable/dev", file.presentableUrl)
            assertEquals(file.displayPath, fs.extractPresentableUrl(file.path))
            file.environment = "development"
            assertEquals("/presentable/development/dev", file.presentableUrl)
            assertEquals(file.displayPath, fs.extractPresentableUrl(file.path))
            assertFalse(file.toString().contains(file.authContextId))
        } finally {
            fs.forget(file)
        }
    }

    fun testForgottenFilePresentationDoesNotExposeAuthContext() {
        val fs = DopplerFileSystem.getInstance()
        val projectSlugs =
            listOf(
                "forgotten",
                "team/backend",
                "team/nested/backend",
                "team%2Fbackend",
                "team +%?#",
                "\u9879\u76ee/\u540e\u7aef",
            )
        val configNames =
            listOf(
                "dev",
                "dev.yaml",
                "team/dev",
                "team/nested/dev.yaml",
                "team%2Fdev +%?#",
                "\u5f00\u53d1/dev",
            )
        for (projectSlug in projectSlugs) {
            for (configName in configNames) {
                val file =
                    fs.obtain(projectSlug, configName, "synthetic-auth-fingerprint").apply {
                        environment = "development"
                    }
                fs.forget(file)

                assertNull(fs.findFileByPath(file.path))
                assertEquals("/$projectSlug/$configName", fs.extractPresentableUrl(file.path))
                assertEquals("/$projectSlug/$configName", file.presentableUrl)
                assertFalse(file.toString().contains(file.authContextId))
            }
        }
    }

    fun testInternalFilePathsEncodeEachIdentityComponentWithoutChangingYamlNames() {
        val fs = DopplerFileSystem.getInstance()
        val file = fs.obtain("team/sub% +", "nested/dev% +.yaml", "ctx/one% +")
        try {
            assertEquals("/team%2Fsub%25%20%2B/ctx%2Fone%25%20%2B/nested%2Fdev%25%20%2B.yaml.yaml", file.path)
            assertEquals("nested/dev% +.yaml.yaml", file.name)
            assertEquals("nested/dev% +.yaml", file.presentableName)
            assertEquals("/team/sub% +/nested/dev% +.yaml", file.presentableUrl)
            assertSame(file, VirtualFileManager.getInstance().findFileByUrl(file.url))
        } finally {
            fs.forget(file)
        }
    }

    fun testSlashContainingIdentityComponentsCannotCollideInTheFileCache() {
        val fs = DopplerFileSystem.getInstance()
        val identities =
            listOf(
                Triple("team/project", "dev", "ctx"),
                Triple("team", "dev", "project/ctx"),
                Triple("team", "ctx/dev", "project"),
                Triple("team%2Fproject", "dev", "ctx"),
            )
        val files =
            identities.map { (projectSlug, configName, authContextId) ->
                fs.obtain(projectSlug, configName, authContextId)
            }
        try {
            assertEquals(identities.size, files.map { it.path }.distinct().size)
            assertEquals(identities.size, files.map { it.url }.distinct().size)
            identities.zip(files).forEach { (identity, file) ->
                assertEquals(identity.first, file.projectSlug)
                assertEquals(identity.second, file.configName)
                assertEquals(identity.third, file.authContextId)
                assertSame(file, fs.obtain(identity.first, identity.second, identity.third))
                assertSame(file, fs.findFileByPath(file.path))
                assertSame(file, VirtualFileManager.getInstance().findFileByUrl(file.url))
            }
        } finally {
            files.forEach(fs::forget)
        }
    }

    fun testMalformedPresentationPathsFailWithoutExposingTheirContents() {
        val fs = DopplerFileSystem.getInstance()
        val paths =
            listOf(
                "/team/backend/synthetic-auth-fingerprint/dev.yaml",
                "/team/synthetic-auth-fingerprint/dev",
                "team/synthetic-auth-fingerprint/dev.yaml",
                "/",
            )
        for (path in paths) {
            try {
                fs.extractPresentableUrl(path)
                fail("Malformed presentation paths must not be displayed")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Invalid Doppler file path", expected.message)
            }
        }
    }

    fun testFriendlyUrlsPreserveAuthScopedFileIdentity() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("account-presentation", "dev", "first-context")
        val second = fs.obtain("account-presentation", "dev", "second-context")
        try {
            assertEquals("/account-presentation/dev", first.presentableUrl)
            assertEquals(first.presentableUrl, second.presentableUrl)
            assertFalse(first.path == second.path)
            assertFalse(first.url == second.url)
            assertSame(first, fs.findFileByPath(first.path))
            assertSame(second, fs.findFileByPath(second.path))
        } finally {
            fs.forget(first)
            fs.forget(second)
        }
    }

    fun testAutoRefreshPausesForEditsAndResumesAfterUndoDespiteUnsavedFlag() {
        val snapshot = mapOf("A" to "old")
        val initialText = SecretsDocumentFormat.serialize(snapshot)
        val file = newFile("refresh-undo", initialText, snapshot)
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val service = DopplerSecretsEditorService.getInstance(project)
        assertTrue(service.canAutoRefresh(file))

        WriteAction.run<RuntimeException> { document.setText("A: changed") }
        assertFalse(service.canAutoRefresh(file))
        WriteAction.run<RuntimeException> { document.setText(initialText) }
        assertTrue(documents.isDocumentUnsaved(document))
        assertTrue(service.canAutoRefresh(file))
        // The same guard rejects edits started while a refresh is in flight.
        WriteAction.run<RuntimeException> { document.setText("A: changed again") }
        assertFalse(service.canAutoRefresh(file))
    }

    fun testAutoRefreshRequiresActiveWindowAndSelectedCleanConfig() {
        val text = SecretsDocumentFormat.serialize(emptyMap())
        val selected = newFile("refresh-selected", text, emptyMap())
        val background = newFile("refresh-background", text, emptyMap())
        val service = DopplerSecretsEditorService.getInstance(project)
        assertEmpty(service.autoRefreshFiles(false, arrayOf(selected)))
        assertEmpty(service.autoRefreshFiles(true, arrayOf(LightVirtualFile("ordinary.yaml"))))
        assertEmpty(service.autoRefreshFiles(true, emptyArray()))
        assertEquals(listOf(selected), service.autoRefreshFiles(true, arrayOf(selected)))
        assertFalse(service.autoRefreshFiles(true, arrayOf(selected)).contains(background))
        selected.hasPendingSync = true
        assertEmpty(service.autoRefreshFiles(true, arrayOf(selected)))
        assertEquals(listOf(background), service.autoRefreshFiles(true, arrayOf(selected, background)))
    }

    fun testAutoRefreshResumesAfterSuccessfulSyncButNotLocalSave() {
        val file = newFile("refresh-save", SecretsDocumentFormat.serialize(mapOf("A" to "old")), mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val service = DopplerSecretsEditorService.getInstance(project)
        val savedSnapshot = mapOf("A" to "new")
        val savedText = SecretsDocumentFormat.serialize(savedSnapshot)
        WriteAction.run<RuntimeException> {
            document.setText(savedText)
            documents.saveDocument(document)
        }
        assertFalse(service.canAutoRefresh(file))
        file.pushInProgress.set(true)
        file.setServerContent(savedText, savedSnapshot)
        assertFalse(service.canAutoRefresh(file))
        file.pushInProgress.set(false)
        assertTrue(service.canAutoRefresh(file))

        WriteAction.run<RuntimeException> { document.setText("A: newer edit") }
        assertFalse(service.canAutoRefresh(file))
    }

    fun testAutoRefreshSkipsPendingFailedQueuedAndInvalidatedConfigs() {
        val file = newFile("refresh-pending", SecretsDocumentFormat.serialize(emptyMap()), emptyMap())
        val other = newFile("refresh-other", SecretsDocumentFormat.serialize(emptyMap()), emptyMap())
        val service = DopplerSecretsEditorService.getInstance(project)
        assertTrue(service.canAutoRefresh(file))
        file.hasPendingSync = true
        assertFalse(service.canAutoRefresh(file))
        assertTrue(service.canAutoRefresh(other))
        file.hasPendingSync = false
        file.pendingPushText.set("A: queued")
        assertFalse(service.canAutoRefresh(file))
        file.pendingPushText.set(null)
        service.push(file, "A: [unclosed")
        assertFalse(service.canAutoRefresh(file))
        file.scrub()
        assertFalse(service.canAutoRefresh(file))
    }

    fun testCloseCheckIsRegisteredAndLeavesOrdinaryFilesUnaffected() {
        val check =
            VirtualFilePreCloseCheck.EP_NAME.extensionList
                .filterIsInstance<DopplerFileCloseCheck>()
                .single()
        assertTrue(check.canCloseFile(LightVirtualFile("ordinary.yaml")))
        assertTrue(check.canCloseFile(newFile("close-clean", "A: old", mapOf("A" to "old"))))
    }

    fun testCancelClosePreservesUnsavedDocumentAndPendingSync() {
        val file = newFile("close-cancel", "A: old", mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: new") }
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 2 })
        assertEquals("A: new", document.text)
        assertTrue(documents.isDocumentUnsaved(document))
        file.hasPendingSync = true
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { -1 })
        assertTrue(file.hasPendingSync)
    }

    fun testDontSaveDiscardsEditsWithoutPushingToDoppler() {
        val file = newFile("close-discard", "A: old", mapOf("A" to "old"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: new") }
        file.hasPendingSync = true
        assertTrue(DopplerFileCloseCheck().confirmClose(file, project) { 1 })
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
        assertFalse(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
        assertFalse(documents.isDocumentUnsaved(document))
        assertEquals(SecretsDocumentFormat.serialize(mapOf("A" to "old")), document.text)
    }

    fun testSavePermitsClosingWhenDocumentAlreadyMatchesDoppler() {
        val text = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val file = newFile("close-save", text, mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        file.hasPendingSync = true
        assertTrue(DopplerFileCloseCheck().confirmClose(file, project) { 0 })
        assertFalse(file.hasPendingSync)
    }

    fun testInvalidSaveKeepsTheTabOpenAndRetainsLocalEdits() {
        val file = newFile("close-invalid", "A: old", mapOf("A" to "old"))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteAction.run<RuntimeException> { document.setText("A: [unclosed") }
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 0 })
        assertEquals("A: [unclosed", document.text)
        assertTrue(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
    }

    fun testSaveDefersClosingUntilSyncCompletesWithoutPendingEdits() {
        val file = newFile("close-deferred", "A: old", mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        val service = DopplerSecretsEditorService.getInstance(project)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        var closes = 0
        assertFalse(service.saveBeforeClose(file) { closes++ })
        assertEquals(0, closes)
        file.pushInProgress.set(false)
        file.hasPendingSync = false
        service.finishCloseRequest(file)
        assertEquals(1, closes)
        service.finishCloseRequest(file)
        assertEquals(1, closes)
    }

    fun testCancellingAPendingCloseRequestPreventsDelayedClosure() {
        val file = newFile("close-cancel-deferred", "A: old", mapOf("A" to "old"))
        FileDocumentManager.getInstance().getDocument(file)
        val service = DopplerSecretsEditorService.getInstance(project)
        file.hasPendingSync = true
        file.pushInProgress.set(true)
        var closes = 0
        assertFalse(service.saveBeforeClose(file) { closes++ })
        assertFalse(DopplerFileCloseCheck().confirmClose(file, project) { 2 })
        file.pushInProgress.set(false)
        file.hasPendingSync = false
        service.finishCloseRequest(file)
        assertEquals(0, closes)
    }

    fun testRegisteredFileIconProviderOnlyOverridesDopplerFiles() {
        val provider =
            FileIconProvider.EP_NAME.extensionList
                .filterIsInstance<DopplerFileIconProvider>()
                .single()
        val file = DopplerFileSystem.getInstance().obtain("icons", "dev", "test-context")
        val setupState = DopplerFolderSetupState.getInstance(project)
        val originalSetup = setupState.current

        try {
            setupState.update(DopplerFolderSetup())
            assertSame(DopplerIcons.EditorTab, provider.getIcon(file, 0, project))

            setupState.update(DopplerFolderSetup("icons", "prod"))
            assertSame(DopplerIcons.EditorTab, provider.getIcon(file, 0, project))

            setupState.update(DopplerFolderSetup("icons", "dev"))
            repeat(3) {
                val bookmarked = requireNotNull(provider.getIcon(file, 0, project))
                assertSame(DopplerIcons.BookmarkedEditorTab, bookmarked)
                assertTrue(bookmarked.iconWidth > 0)
                assertTrue(bookmarked.iconHeight > 0)
                assertEquals(DopplerIcons.EditorTab.iconWidth, bookmarked.iconWidth)
                assertEquals(DopplerIcons.EditorTab.iconHeight, bookmarked.iconHeight)
            }

            assertNotNull(DopplerIcons::class.java.getResource("/icons/doppler_tab.svg"))
            assertNotNull(DopplerIcons::class.java.getResource("/icons/doppler_tab_bookmarked.svg"))
            assertEquals("YAML", file.fileType.name)
            assertNull(provider.getIcon(LightVirtualFile("ordinary.yaml"), 0, project))
        } finally {
            setupState.update(originalSetup)
        }
    }

    fun testFolderSetupChangesRefreshOpenDopplerTabIcons() {
        val dopplerFile = DopplerFileSystem.getInstance().obtain("icons", "dev", "test-context")
        val ordinaryFile = LightVirtualFile("ordinary.yaml")
        val refreshed = mutableListOf<VirtualFile>()

        DopplerFolderSetupTabIconUpdater(
            openFiles = { arrayOf(dopplerFile, ordinaryFile) },
            updatePresentation = refreshed::add,
        ).changed()

        assertEquals(listOf(dopplerFile), refreshed)
    }

    fun testDuplicateConfigTabsIncludeProjectAndEnvironment() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-backend", "dev", "test-context").apply { environment = "development" }
        val second = fs.obtain("tab-frontend", "dev", "test-context").apply { environment = "dev" }
        val manager = FileEditorManager.getInstance(project)
        val provider = DopplerEditorTabTitleProvider()
        manager.openFile(first, false)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.openFile(second, false)

        assertEquals("dev /tab-backend/development/dev", provider.getEditorTabTitle(project, first))
        assertEquals("dev /tab-frontend/dev/dev", provider.getEditorTabTitle(project, second))
        assertEquals("/tab-backend/development/dev", first.presentableUrl)
        assertEquals("/tab-frontend/dev/dev", second.presentableUrl)

        manager.closeFile(second)
        assertEquals("dev", provider.getEditorTabTitle(project, first))
        manager.closeFile(first)
    }

    fun testDuplicateConfigTitleHandlesMissingEnvironmentMetadata() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("tab-known", "stg", "test-context")
        val second = fs.obtain("tab-other", "stg", "test-context")
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(first, false)
        manager.openFile(second, false)

        val provider = DopplerEditorTabTitleProvider()
        val title = provider.getEditorTabTitle(project, first)
        assertEquals("stg /tab-known/stg", title)
        assertEquals("/tab-known/stg", first.presentableUrl)
        assertNull(provider.getEditorTabTitle(project, LightVirtualFile("ordinary.yaml")))
        manager.closeFile(second)
        manager.closeFile(first)
    }

    fun testTabPresentationUsesBoldConfigAndRegularPathAndSurvivesTitleResets() {
        val fs = DopplerFileSystem.getInstance()
        val first = fs.obtain("bold-backend", "dev", "test-context").apply { environment = "dev" }
        val second = fs.obtain("bold-frontend", "dev", "test-context").apply { environment = "dev" }
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(first, false)
        manager.openFile(second, false)
        val presentation = DopplerEditorTabPresentation(project)
        try {
            val tab = TabInfo(JPanel()).setObject(first)
            presentation.update(tab)
            assertEquals(listOf("dev", " /bold-backend/dev/dev"), tab.coloredText.texts)
            assertEquals(SimpleTextAttributes.STYLE_BOLD, tab.coloredText.attributes[0].style)
            assertEquals(SimpleTextAttributes.STYLE_PLAIN, tab.coloredText.attributes[1].style)

            tab.setText("dev /bold-backend/dev/dev")
            assertEquals(SimpleTextAttributes.STYLE_BOLD, tab.coloredText.attributes[0].style)
            assertEquals(SimpleTextAttributes.STYLE_PLAIN, tab.coloredText.attributes[1].style)
            manager.closeFile(second)
            presentation.update(tab)
            assertEquals(listOf("dev"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
            manager.closeFile(second)
            manager.closeFile(first)
        }
    }

    fun testTabPresentationMarksUnsavedEditsWhileTyping() {
        val initialText = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val editedText = SecretsDocumentFormat.serialize(mapOf("A" to "changed"))
        val file = newFile("editing", initialText, mapOf("A" to "old"))
        val presentation = DopplerEditorTabPresentation(project)
        val tab = TabInfo(JPanel()).setObject(file)
        try {
            presentation.update(tab)
            assertEquals(listOf("editing"), tab.coloredText.texts)

            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            WriteAction.run<RuntimeException> { document.setText(editedText) }

            assertEquals(listOf("editing", " *"), tab.coloredText.texts)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

            WriteAction.run<RuntimeException> { document.setText(initialText) }

            assertTrue(
                "the IDE still considers the reverted document unsaved",
                FileDocumentManager.getInstance().isDocumentUnsaved(document),
            )
            assertEquals("undoing back to the server content clears the edit marker", listOf("editing"), tab.coloredText.texts)

            WriteAction.run<RuntimeException> { document.setText(editedText) }
            assertEquals(listOf("editing", " *"), tab.coloredText.texts)

            file.suppressPush = true
            try {
                WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(document) }
            } finally {
                file.suppressPush = false
            }
            file.setServerContent(editedText, mapOf("A" to "changed"))
            presentation.update(tab)

            assertEquals(listOf("editing"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
        }
    }

    fun testRealEditorTabPresentationTracksUndoToServerContent() {
        val initialText = SecretsDocumentFormat.serialize(mapOf("A" to "old"))
        val editedText = SecretsDocumentFormat.serialize(mapOf("A" to "changed"))
        val file = newFile("real-tab-editing", initialText, mapOf("A" to "old"))
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.openFile(file, false).single()
        val presentation = DopplerEditorTabPresentation(project)
        val tab = TabInfo(JPanel()).setObject(editor)
        try {
            presentation.update(tab)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

            WriteAction.run<RuntimeException> { document.setText(editedText) }
            assertEquals(listOf("real-tab-editing", " *"), tab.coloredText.texts)

            WriteAction.run<RuntimeException> { document.setText(initialText) }
            assertEquals(listOf("real-tab-editing"), tab.coloredText.texts)
        } finally {
            presentation.dispose()
            manager.closeFile(file)
        }
    }

    fun testEditingMarksTheDocumentUnsavedAndSavingWritesThroughToTheFile() {
        val file = newFile("dev", "API_KEY: old", mapOf("API_KEY" to "old"))
        val manager = FileDocumentManager.getInstance()
        val document = requireNotNull(manager.getDocument(file))

        assertEquals("API_KEY: old", document.text)

        WriteAction.run<RuntimeException> { document.setText("API_KEY: new") }
        assertTrue("document should be pending save", manager.isDocumentUnsaved(document))

        WriteAction.run<RuntimeException> { manager.saveDocument(document) }

        assertFalse("document should be saved", manager.isDocumentUnsaved(document))
        assertTrue("local save should remain pending for explicit remote sync", file.hasPendingSync)
        assertFalse("local save must not start a remote push", file.pushInProgress.get())
        assertEquals(
            "API_KEY: new",
            String(file.contentsToByteArray(), StandardCharsets.UTF_8),
        )
    }

    fun testTheFileSystemReturnsTheSameInstanceForAConfig() {
        val fs = DopplerFileSystem.getInstance()
        assertSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "stg", "test-context"))
        assertNotSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "prd", "test-context"))
        assertNotSame(fs.obtain("backend", "stg", "test-context"), fs.obtain("backend", "stg", "other-context"))
    }

    fun testInvalidatingAnotherAuthContextClosesAndScrubsTheCachedEditor() {
        val fs = DopplerFileSystem.getInstance()
        val secretText = SecretsDocumentFormat.serialize(mapOf("API_TOKEN" to "secret-value"))
        val file = newFile("auth-scrub", secretText, mapOf("API_TOKEN" to "secret-value"))
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(file))
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, false)

        DopplerSecretsEditorService.invalidateCachedFiles("other-context")

        assertTrue(file.isInvalidated)
        assertFalse(file.isWritable)
        assertTrue(file.remoteSnapshot.isEmpty())
        assertFalse(String(file.contentsToByteArray(), StandardCharsets.UTF_8).contains("secret-value"))
        assertFalse(document.text.contains("secret-value"))
        assertFalse(manager.isFileOpen(file))
        assertNull(fs.findFileByPath(file.path))
    }

    fun testTheFileIsResolvableByPathAndIsWritableYaml() {
        val file = newFile("prd", DopplerSecretsFile.EMPTY_DOCUMENT, emptyMap())

        assertTrue(file.isWritable)
        assertEquals("YAML", file.fileType.name)
        assertEquals("prd.yaml", file.name)
        assertEquals("prd", file.presentableName)
        assertSame(file, DopplerFileSystem.getInstance().findFileByPath(file.path))
    }

    fun testNavigationLabelShowsConfigNameWithoutSyntheticYamlExtension() {
        val file = newFile("stg", DopplerSecretsFile.EMPTY_DOCUMENT, emptyMap())
        myFixture.configureFromExistingVirtualFile(file)
        val psiFile = myFixture.file

        assertEquals(
            "stg",
            com.intellij.ide.navigationToolbar
                .DefaultNavBarExtension()
                .getPresentableText(psiFile),
        )
        assertEquals("YAML", psiFile.fileType.name)
    }

    fun testReloadingServerContentDoesNotPushBack() {
        val file = newFile("ci", "A: '1'", mapOf("A" to "1"))
        file.suppressPush = true
        file.setServerContent("A: '2'", mapOf("A" to "2"))
        file.suppressPush = false

        assertEquals(mapOf("A" to "2"), file.remoteSnapshot)
        assertEquals("A: '2'", String(file.contentsToByteArray(), StandardCharsets.UTF_8))
    }

    fun testASavedLocalEditRemainsPendingUntilSynchronized() {
        val file = newFile("pending", "A: old", mapOf("A" to "old"))
        assertFalse(file.hasPendingSync)
        file.getOutputStream(null, -1, -1).use { it.write("A: new".toByteArray(StandardCharsets.UTF_8)) }
        assertTrue(file.hasPendingSync)

        file.setServerContent("A: new", mapOf("A" to "new"))
        assertFalse(file.hasPendingSync)
    }

    fun testARejectedSaveRemainsProtectedFromAutoRefresh() {
        val file = newFile("invalid", "A: old", mapOf("A" to "old"))
        DopplerSecretsEditorService.getInstance(project).push(file, "A: [unclosed")

        assertTrue(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
    }

    fun testASaveWithoutRemoteChangesClearsThePendingState() {
        val file = newFile("unchanged", "A: old", mapOf("A" to "old"))
        file.hasPendingSync = true
        DopplerSecretsEditorService.getInstance(project).push(file, "A: old")

        assertFalse(file.hasPendingSync)
        assertFalse(file.pushInProgress.get())
    }

    fun testSavesDuringASyncKeepOnlyTheNewestPendingDocument() {
        val file = newFile("queued", "A: old", mapOf("A" to "old"))
        val service = DopplerSecretsEditorService.getInstance(project)
        file.pushInProgress.set(true)

        service.push(file, "A: first")
        service.push(file, "A: latest")

        assertTrue(file.hasPendingSync)
        assertEquals("A: latest", file.pendingPushText.get())
        assertEquals(mapOf("A" to "old"), file.remoteSnapshot)
        file.pushInProgress.set(false)
        file.pendingPushText.set(null)
    }
}
