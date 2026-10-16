package com.vk.doppler.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.vk.doppler.service.DopplerService
import com.vk.doppler.service.DopplerStatus
import com.vk.doppler.service.DopplerStatusReport

class DopplerConfigurable : BoundConfigurable("Doppler") {

    private val settings = DopplerSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row("Doppler CLI path:") {
            textFieldWithBrowseButton(
                fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle("Select Doppler CLI Executable"),
            )
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
        row {
            checkBox("Log CLI calls")
                .bindSelected(settings::logCliCalls)
                .comment("Log commands, durations, and exit status to idea.log. Project, config, and environment names show only their first 60%. " +
                    "Secret values and CLI output are never included. Open Help → Show Log in Finder/Explorer to view it.")
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
                            connectionSuccessMessage(report),
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

/** Only selected identity fields are rendered; arbitrary CLI response fields are never displayed. */
internal fun connectionSuccessMessage(report: DopplerStatusReport): String {
    fun escape(value: String) = StringUtil.escapeXmlEntities(value)
    return buildString {
        append("<html>Connected to Doppler.<br>")
        report.account?.let { account ->
            val identity = account.name?.takeIf { it.isNotBlank() }?.let { "$it (${account.slug})" } ?: account.slug
            append("<br>Identity: ${escape(identity)}")
            append("<br>Identity type: ${escape(account.type)}")
            val workplace = account.workplaceName?.let { "$it (${account.workplaceSlug})" } ?: account.workplaceSlug
            append("<br>Workplace: ${escape(workplace)}<br>")
        }
        append("<br>CLI version: ${escape(report.detail.orEmpty())}</html>")
    }
}
