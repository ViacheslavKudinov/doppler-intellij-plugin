package com.vk.doppler

import com.intellij.ide.plugins.DynamicPluginVetoer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DopplerPluginUnloadVetoerTest : BasePlatformTestCase() {
    private fun registeredVetoer(): DopplerPluginUnloadVetoer =
        ExtensionPointName
            .create<DynamicPluginVetoer>("com.intellij.ide.dynamicPluginVetoer")
            .extensionList
            .filterIsInstance<DopplerPluginUnloadVetoer>()
            .single()

    fun testRegisteredVetoerBlocksUnloadingDoppler() {
        val descriptor =
            requireNotNull(
                PluginManagerCore.getPlugin(PluginId.getId("com.viacheslavkudinov.doppler")),
            )

        assertEquals(
            "Restart the IDE to disable, remove, or update Doppler Secrets Manager.",
            registeredVetoer().vetoPluginUnload(descriptor),
        )
    }

    fun testRegisteredVetoerLeavesOtherPluginsUnaffected() {
        val descriptor = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("com.intellij")))

        assertNull(registeredVetoer().vetoPluginUnload(descriptor))
    }
}
