package com.vk.doppler.editor

import com.intellij.ide.navigationToolbar.NavBarModelExtension
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vk.doppler.DopplerIcons
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.settings.DopplerFolderSetupState

class DopplerNavBarExtensionTest : BasePlatformTestCase() {
    fun testSwitchingSameNamedConfigsChangesNavigationIdentityAndBookmark() {
        val extensions = NavBarModelExtension.EP_NAME.extensionList
        val extension = extensions.filterIsInstance<DopplerNavBarExtension>().single()
        val first = DopplerFileSystem.getInstance().obtain("navbar-first", "aws-dev", "test-context")
        val second = DopplerFileSystem.getInstance().obtain("navbar-second", "aws-dev", "test-context")
        val psi = PsiManager.getInstance(project)
        val firstPsi = requireNotNull(psi.findFile(first))
        val secondPsi = requireNotNull(psi.findFile(second))
        val state = DopplerFolderSetupState.getInstance(project)
        val saved = state.current
        try {
            state.update(DopplerFolderSetup("navbar-first", "aws-dev"))
            assertEquals("aws-dev", extension.getPresentableText(firstPsi, false))
            assertEquals("aws-dev", extension.getPresentableText(secondPsi, false))
            assertEquals("/navbar-first/aws-dev (default config)", extension.getPresentableText(firstPsi, true))
            assertEquals("/navbar-second/aws-dev", extension.getPresentableText(secondPsi, true))
            assertEquals(
                "/navbar-first/aws-dev (default config)",
                extensions.firstNotNullOfOrNull { it.getPresentableText(firstPsi, true) },
            )
            assertFalse(extension.getPresentableText(firstPsi, true) == extension.getPresentableText(secondPsi, true))
            repeat(3) {
                assertSame(DopplerIcons.BookmarkedEditorTab, extension.getIcon(firstPsi))
                assertSame(DopplerIcons.EditorTab, extension.getIcon(secondPsi))
            }
            state.update(DopplerFolderSetup("navbar-second", "aws-dev"))
            assertEquals("/navbar-first/aws-dev", extension.getPresentableText(firstPsi, true))
            assertEquals("/navbar-second/aws-dev (default config)", extension.getPresentableText(secondPsi, true))
            assertSame(DopplerIcons.EditorTab, extension.getIcon(firstPsi))
            assertSame(DopplerIcons.BookmarkedEditorTab, extension.getIcon(secondPsi))
        } finally {
            state.update(saved)
        }
    }

    fun testClearingDefaultChangesThePresentationEvenWhenTheConfigNameStaysTheSame() {
        val extension = DopplerNavBarExtension()
        val file = DopplerFileSystem.getInstance().obtain("navbar-default", "dev", "test-context")
        file.environment = "development"
        val psi = requireNotNull(PsiManager.getInstance(project).findFile(file))
        val state = DopplerFolderSetupState.getInstance(project)
        val saved = state.current
        try {
            state.update(DopplerFolderSetup("navbar-default", "dev"))
            val bookmarked = extension.getPresentableText(psi, true)
            state.update(DopplerFolderSetup())
            assertEquals("dev", extension.getPresentableText(psi))
            assertEquals("/navbar-default/development/dev", extension.getPresentableText(psi, true))
            assertFalse(bookmarked == extension.getPresentableText(psi, true))
            assertSame(DopplerIcons.EditorTab, extension.getIcon(psi))
        } finally {
            state.update(saved)
        }
    }

    fun testOrdinaryFilesAndNonFileElementsKeepTheirNativeNavigationPresentation() {
        val extension = DopplerNavBarExtension()
        val ordinary = myFixture.configureByText("ordinary.yaml", "A: value")
        for (value in listOf(ordinary, project)) {
            assertNull(extension.getPresentableText(value))
            assertNull(extension.getPresentableText(value, true))
            assertNull(extension.getIcon(value))
        }
    }
}
