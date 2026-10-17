package com.vk.doppler.toolwindow

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.DopplerFolderSetup
import com.vk.doppler.settings.DopplerFolderSetupListener
import com.vk.doppler.settings.DopplerFolderSetupState
import com.vk.doppler.settings.DopplerSettings
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DopplerFolderSetupRefreshTest : BasePlatformTestCase() {
    fun `test tree refresh preserves bookmark on read failure but clears it on successful empty read`() {
        assumeFalse(SystemInfo.isWindows)
        val directory = Files.createTempDirectory("doppler-tree-folder-")
        val executable = directory.resolve("doppler")
        val mode = directory.resolve("mode")
        Files.writeString(mode, "failure")
        Files.writeString(executable, """
            #!/bin/sh
            case "${'$'}1" in
              me) printf '%s' '{"slug":"test","type":"personal","token_preview":"synthetic","workplace":{"slug":"test"}}' ;;
              projects) printf '[]' ;;
              configure)
                if [ "${'$'}(cat '$mode')" = failure ]; then
                  printf '%s' '{"error":"Temporary configuration read failure"}'
                  exit 1
                fi
                printf '{}'
                ;;
              *) exit 1 ;;
            esac
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val state = DopplerFolderSetupState.getInstance(project)
        val originalSetup = state.current
        val configured = DopplerFolderSetup("example", "dev")
        var window: DopplerToolWindowPanel? = null
        try {
            settings.cliPath = executable.toString()
            state.update(configured)
            var notifications = 0
            project.messageBus.connect(testRootDisposable).subscribe(
                DopplerFolderSetupState.TOPIC, DopplerFolderSetupListener { notifications++ },
            )
            val panel = DopplerToolWindowPanel(project)
            window = panel
            awaitRefresh(panel)
            assertEquals(configured, state.current)
            assertEquals("A failed read must not publish an empty setup", 0, notifications)

            Files.writeString(mode, "empty")
            panel.refresh()
            awaitRefresh(panel)
            assertEquals(DopplerFolderSetup(), state.current)
            assertEquals("A successful empty read must remove the bookmark", 1, notifications)
        } finally {
            window?.let { Disposer.dispose(it) }
            settings.cliPath = originalPath
            state.update(originalSetup)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun awaitRefresh(panel: DopplerToolWindowPanel) {
        // Observe task completion without adding a test-only API to the tool window.
        val refreshing = DopplerToolWindowPanel::class.java.getDeclaredField("refreshing").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (refreshing.getBoolean(panel) && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertFalse("Tree refresh should finish", refreshing.getBoolean(panel))
    }
}
