package com.vk.doppler.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport

class DopplerConfigurable : BoundConfigurable("Doppler") {

    private val settings = DopplerSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row("Doppler CLI path:") {
            textField()
                .bindText(settings::cliPath)
                .align(AlignX.FILL)
                .comment(
                    "Leave empty to auto-detect the <code>doppler</code> binary on PATH and in common " +
                        "install locations. Authentication is handled by <code>doppler login</code>; " +
                        "this plugin never stores your token.",
                )
        }
        row {
            intTextField(range = 0..3600)
                .bindIntText(settings::refreshIntervalSeconds)
                .label("Re-read open configs every:")
                .gap(RightGap.SMALL)
            label("seconds (0 disables auto-refresh)")
        }
        row {
            button("Test Connection") { testConnection() }
        }
    }

    private fun testConnection() {
        apply()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Checking Doppler CLI…", false) {
                private lateinit var report: DopplerStatusReport

                override fun run(indicator: ProgressIndicator) {
                    report = DopplerService.getInstance().checkStatus()
                }

                override fun onSuccess() {
                    if (report.status == DopplerStatus.READY) {
                        Messages.showInfoMessage(
                            "Connected to Doppler. CLI version: ${report.detail.orEmpty()}",
                            "Doppler",
                        )
                    } else {
                        Messages.showErrorDialog(report.detail ?: report.status.name, "Doppler")
                    }
                }
            },
        )
    }
}
