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
    private lateinit var savedConsent: DopplerInjectionConsent.State

    override fun setUp() {
        super.setUp()
        savedConsent = DopplerInjectionConsent.getInstance().state
        DopplerInjectionConsent.getInstance().loadState(DopplerInjectionConsent.State())
    }

    override fun tearDown() {
        try {
            DopplerInjectionConsent.getInstance().loadState(savedConsent)
        } finally { super.tearDown() }
    }

    fun `test defaults and persistence contain only injection preferences`() {
        val settings = DopplerInjectionSettings(project)
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        settings.enabled = true
        for (transformer in DopplerNameTransformer.entries) {
            settings.nameTransformer = transformer
            val serialized = XmlSerializer.serialize(settings.state)
            val restored = DopplerInjectionSettings(project)
            restored.loadState(XmlSerializer.deserialize(serialized, DopplerInjectionSettings.State::class.java))
            assertEquals(settings.state, restored.state)
            assertTrue(serialized.getChildren("option").all { it.getAttributeValue("name") in setOf("enabled", "nameTransformer") })
        }
    }

    fun `test legacy workspace opt in cannot enable injection without local approval`() {
        val legacy = Element("state")
            .addContent(Element("option").setAttribute("name", "enabled").setAttribute("value", "true"))
            .addContent(Element("option").setAttribute("name", "durationMinutes").setAttribute("value", "12"))
        val settings = DopplerInjectionSettings(project)
        settings.loadState(XmlSerializer.deserialize(legacy, DopplerInjectionSettings.State::class.java))
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.NONE, settings.nameTransformer)
        val serialized = XmlSerializer.serialize(settings.state)
        assertEquals(setOf("enabled", "nameTransformer"), serialized.getChildren("option").map { it.getAttributeValue("name") }.toSet())
    }

    fun `test workspace changes cannot grant or restore revoked approval`() {
        val settings = DopplerInjectionSettings(project)
        settings.loadState(DopplerInjectionSettings.State(true, DopplerNameTransformer.CAMEL))
        assertFalse(settings.enabled)
        assertEquals(DopplerNameTransformer.CAMEL, settings.nameTransformer)
        settings.enabled = true
        assertTrue(settings.enabled)
        val reopened = DopplerInjectionSettings(project)
        reopened.loadState(settings.state.copy())
        assertTrue(reopened.enabled)
        settings.enabled = false
        reopened.loadState(DopplerInjectionSettings.State(true))
        assertFalse(reopened.enabled)
    }

    fun `test consent is user local project scoped and persists without sharing mutable state`() {
        val storage = DopplerInjectionConsent::class.java.getAnnotation(com.intellij.openapi.components.State::class.java).storages.single()
        assertEquals("doppler-injection-consent.xml", storage.value)
        assertEquals(com.intellij.openapi.components.RoamingType.DISABLED, storage.roamingType)
        val consent = DopplerInjectionConsent()
        assertFalse(consent.isGranted("/synthetic/project-one"))
        consent.setGranted(null, true)
        consent.setGranted("", true)
        assertTrue(consent.state.approvedProjects.isEmpty())
        consent.setGranted("/synthetic/project-one", true)
        assertTrue(consent.isGranted("/synthetic/project-one"))
        assertFalse(consent.isGranted("/synthetic/project-two"))
        val serialized = XmlSerializer.serialize(consent.state)
        val restored = DopplerInjectionConsent()
        val state = XmlSerializer.deserialize(serialized, DopplerInjectionConsent.State::class.java)
        restored.loadState(state)
        state.approvedProjects.clear()
        restored.state.approvedProjects.clear()
        assertTrue(restored.isGranted("/synthetic/project-one"))
        restored.setGranted("/synthetic/project-one", false)
        assertFalse(restored.isGranted("/synthetic/project-one"))
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
