package com.vk.doppler

import com.intellij.openapi.util.IconLoader

/** Mirrors the codicons used by Doppler's VS Code extension: repo, versions, symbol-field. */
object DopplerIcons {
    @JvmField val ToolWindow = IconLoader.getIcon("/icons/doppler.svg", DopplerIcons::class.java)
    @JvmField val EditorTab = IconLoader.getIcon("/icons/doppler_tab.svg", DopplerIcons::class.java)
    @JvmField val Project = IconLoader.getIcon("/icons/repo.svg", DopplerIcons::class.java)
    @JvmField val Environment = IconLoader.getIcon("/icons/versions.svg", DopplerIcons::class.java)
    @JvmField val Config = IconLoader.getIcon("/icons/field.svg", DopplerIcons::class.java)
    @JvmField val ConfigSelected = IconLoader.getIcon("/icons/field_selected.svg", DopplerIcons::class.java)
}
