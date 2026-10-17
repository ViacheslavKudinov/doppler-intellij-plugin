package com.vk.doppler.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.vk.doppler.cli.DopplerFolderSetup
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class DopplerFolderSetupStateTest : BasePlatformTestCase() {
    fun `test newest queued poll wins when the UI thread was busy`() = withCli { state, response ->
        var notifications = 0
        project.messageBus.connect(testRootDisposable).subscribe(
            DopplerFolderSetupState.TOPIC, DopplerFolderSetupListener { notifications++ },
        )
        // Keep the EDT occupied until both reads have queued their callbacks.
        poll(state, response, "older", "dev")
        poll(state, response, "newer", "prod")
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(DopplerFolderSetup("newer", "prod"), state.current)
        assertEquals("The superseded result must not reach subscribers", 1, notifications)
    }

    fun `test applying settings takes precedence over both pending poll results`() = withCli { state, response ->
        poll(state, response, "older", "dev")
        poll(state, response, "newer", "stg")
        val applied = DopplerFolderSetup("manual", "prod")
        state.update(applied)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(applied, state.current)

        // Subsequent polls still refresh normally after a foreground update.
        poll(state, response, "external", "dev")
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(DopplerFolderSetup("external", "dev"), state.current)
    }

    private fun poll(state: DopplerFolderSetupState, response: Path, project: String, config: String) {
        assertTrue("Queue callbacks while the test holds the EDT", SwingUtilities.isEventDispatchThread())
        Files.writeString(response, """{"project":"$project","config":"$config"}""")
        ApplicationManager.getApplication().executeOnPooledThread {
            state.refreshFromCli()
        }.get(10, TimeUnit.SECONDS)
    }

    private fun withCli(test: (DopplerFolderSetupState, Path) -> Unit) {
        assumeFalse(SystemInfo.isWindows)
        val directory = Files.createTempDirectory("doppler-folder-poll-")
        val executable = directory.resolve("doppler")
        val response = directory.resolve("response.json")
        Files.writeString(executable, """
            #!/bin/sh
            if [ "${'$'}1" = configure ] && [ "${'$'}2" = get ]; then
              cat '$response'
            else
              exit 1
            fi
        """.trimIndent())
        assertTrue(executable.toFile().setExecutable(true))
        val settings = DopplerSettings.getInstance()
        val originalPath = settings.cliPath
        val state = DopplerFolderSetupState.getInstance(project)
        val originalSetup = state.current
        try {
            settings.cliPath = executable.toString()
            test(state, response)
        } finally {
            // Finish queued callbacks before restoring shared settings or deleting the fixture.
            UIUtil.dispatchAllInvocationEvents()
            settings.cliPath = originalPath
            state.update(originalSetup)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
