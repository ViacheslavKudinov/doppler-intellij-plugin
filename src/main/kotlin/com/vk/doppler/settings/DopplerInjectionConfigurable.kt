package com.vk.doppler.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.vk.doppler.cli.DopplerNameTransformer

class DopplerInjectionConfigurable(private val project: Project) : BoundConfigurable("Run and Debug") {
    override fun createPanel(): DialogPanel {
        val settings = DopplerInjectionSettings.getInstance(project)
        return panel {
            row {
                checkBox("Automatically inject Doppler secrets into Run and Debug")
                    .bindSelected(settings::enabled)
                    .comment("Uses this project's default Doppler config from Folder Setup. Supports Java, Python, Node.js, Maven, Gradle, and Docker.")
            }
            row("Name transformation:") {
                comboBox(DopplerNameTransformer.entries)
                    .bindItem({ settings.nameTransformer }, { settings.nameTransformer = it ?: DopplerNameTransformer.NONE })
                    .comment("None keeps the original names. Applies only to injected Doppler secrets; values are unchanged.")
            }
            row {
                comment("Every launch fetches fresh secrets without caching them. Existing run configuration variables take precedence. " +
                    "Launched processes keep their inherited environment for their lifetime.")
            }
        }
    }
}
