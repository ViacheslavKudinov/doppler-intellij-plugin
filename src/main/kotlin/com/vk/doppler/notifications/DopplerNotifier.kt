package com.vk.doppler.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

object DopplerNotifier {

    private const val GROUP_ID = "Doppler"
    private const val INFO_TIMEOUT_SECONDS = 3L

    private val notificationConfiguration = NotificationsConfiguration.getNotificationsConfiguration().apply {
        register(GROUP_ID, NotificationDisplayType.BALLOON)
    }

    fun info(project: Project?, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun error(project: Project?, content: String) = notify(project, content, NotificationType.ERROR)

    /**
     * Notification content is rendered as HTML. Messages embed project/config names and CLI
     * output controlled by other workspace members, so escape them to prevent markup injection
     * (e.g. remote `<img>` loads or spoofed links).
     */
    private fun notify(project: Project?, content: String, type: NotificationType) {
        val notification = Notification(GROUP_ID, "Doppler", StringUtil.escapeXmlEntities(content), type)
        notification.notify(project)
        if (type == NotificationType.INFORMATION) {
            AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(notification::expire, INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}
