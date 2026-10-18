package com.vk.doppler.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import com.vk.doppler.cli.DopplerNameTransformer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTextField

class DopplerInjectionSettingsTest : BasePlatformTestCase() {
    fun `test defaults and persistence contain only injection preferences`() {
        val settings = DopplerInjectionSettings()
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        settings.enabled = true
        for (transformer in DopplerNameTransformer.entries) {
            settings.nameTransformer = transformer
            val serialized = XmlSerializer.serialize(settings.state)
            val restored = DopplerInjectionSettings()
            restored.loadState(XmlSerializer.deserialize(serialized, DopplerInjectionSettings.State::class.java))
            assertEquals(settings.state, restored.state)
            assertTrue(serialized.getChildren("option").all { it.getAttributeValue("name") in setOf("enabled", "nameTransformer") })
        }
    }

    fun `test saved expiry preference is ignored while opt in is preserved`() {
        val legacy = Element("state")
            .addContent(Element("option").setAttribute("name", "enabled").setAttribute("value", "true"))
            .addContent(Element("option").setAttribute("name", "durationMinutes").setAttribute("value", "12"))
        val settings = DopplerInjectionSettings()
        settings.loadState(XmlSerializer.deserialize(legacy, DopplerInjectionSettings.State::class.java))
        assertTrue(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        val serialized = XmlSerializer.serialize(settings.state)
        assertEquals(setOf("enabled", "nameTransformer"), serialized.getChildren("option").map { it.getAttributeValue("name") }.toSet())
    }

    fun `test settings page applies and resets opt in without an expiry field`() {
        val settings = DopplerInjectionSettings.getInstance(project)
        val saved = settings.state.copy()
        settings.enabled = false
        settings.nameTransformer = DopplerNameTransformer.NONE
        val page = DopplerInjectionConfigurable(project)
        try {
            val panel = page.createPanel()
            panel.reset()
            val enabled = UIUtil.findComponentOfType(panel, JCheckBox::class.java)!!
            val transformation = UIUtil.findComponentOfType(panel, JComboBox::class.java)!!
            assertEquals(DopplerNameTransformer.entries, (0 until transformation.itemCount).map { transformation.getItemAt(it) })
            assertEquals(DopplerNameTransformer.NONE, transformation.selectedItem)
            assertFalse(enabled.isSelected)
            assertNull(UIUtil.findComponentOfType(panel, JTextField::class.java))
            enabled.isSelected = true
            transformation.selectedItem = DopplerNameTransformer.DOTNET_ENV
            assertTrue(panel.isModified())
            panel.apply()
            assertTrue(settings.enabled)
            assertEquals(DopplerNameTransformer.DOTNET_ENV, settings.nameTransformer)
            enabled.isSelected = false
            transformation.selectedItem = DopplerNameTransformer.CAMEL
            panel.reset()
            assertTrue(enabled.isSelected)
            assertEquals(DopplerNameTransformer.DOTNET_ENV, transformation.selectedItem)
        } finally {
            settings.loadState(saved)
            page.disposeUIResources()
        }
    }
}
