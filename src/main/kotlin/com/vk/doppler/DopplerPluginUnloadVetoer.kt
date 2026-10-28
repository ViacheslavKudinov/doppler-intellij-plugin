package com.vk.doppler

import com.intellij.ide.plugins.DynamicPluginVetoer
import com.intellij.ide.plugins.IdeaPluginDescriptor

class DopplerPluginUnloadVetoer : DynamicPluginVetoer {
    override fun vetoPluginUnload(pluginDescriptor: IdeaPluginDescriptor): String? =
        if (pluginDescriptor.pluginId.idString == "com.viacheslavkudinov.doppler") {
            "Restart the IDE to disable, remove, or update Doppler Secrets Manager."
        } else {
            null
        }
}
