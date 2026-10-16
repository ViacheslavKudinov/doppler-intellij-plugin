package com.vk.doppler.settings

import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import javax.swing.JButton

class DopplerConfigurableTest : BasePlatformTestCase() {
    fun `test CLI browse field preserves manual entry apply reset and automatic detection`() {
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val configurable = DopplerConfigurable()
        try {
            settings.cliPath = "/usr/local/bin/doppler"
            val panel = configurable.createPanel()
            panel.reset()
            val field = requireNotNull(UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java))
            val browseButton = requireNotNull(UIUtil.findComponentOfType(field, JButton::class.java))
            assertTrue(browseButton.isEnabled)
            assertTrue(field.textField.isEditable)
            assertEquals(settings.cliPath, field.text)

            field.text = "/opt/homebrew/bin/doppler"
            assertTrue(panel.isModified())
            panel.apply()
            assertEquals("/opt/homebrew/bin/doppler", settings.cliPath)

            field.text = "/another/path"
            panel.reset()
            assertEquals("/opt/homebrew/bin/doppler", field.text)
            assertFalse(panel.isModified())

            field.text = ""
            panel.apply()
            assertEquals("", settings.cliPath)
        } finally {
            configurable.disposeUIResources()
            settings.cliPath = originalPath
        }
    }
}
