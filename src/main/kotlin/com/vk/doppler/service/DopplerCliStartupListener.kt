package com.vk.doppler.service

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager

internal class DopplerCliStartupListener :
    AppLifecycleListener,
    DynamicPluginListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        scheduleCheck()
    }

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == "com.viacheslavkudinov.doppler") scheduleCheck()
    }

    private fun scheduleCheck() {
        val application = ApplicationManager.getApplication()
        application.invokeLater {
            if (!application.isDisposed) DopplerCliUpdateService.getInstance().checkAutomatically(null)
        }
    }
}
