package com.vk.doppler.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class DopplerInstallerOptionsTest {
    @Test
    fun `installer download accepts only the exact Doppler endpoints`() {
        assertEquals(INSTALL_SCRIPT_URL, trustedInstallerUrl(URI(INSTALL_SCRIPT_URL)).toString())
        assertEquals(INSTALL_SCRIPT_SOURCE_URL, trustedInstallerUrl(URI(INSTALL_SCRIPT_SOURCE_URL)).toString())
    }

    @Test
    fun `installer download rejects untrusted redirect targets`() {
        listOf(
            "http://cli.doppler.com/install.sh",
            "https://cli.doppler.com:8443/install.sh",
            "https://attacker.example/install.sh",
            "https://cli.doppler.com.attacker.example/install.sh",
            "https://127.0.0.1/install.sh",
            "https://169.254.169.254/latest/meta-data/",
            "https://raw.githubusercontent.com/attacker/cli/master/scripts/install.sh",
            "$INSTALL_SCRIPT_SOURCE_URL?unexpected=true",
        ).forEach { target ->
            try {
                trustedInstallerUrl(URI(target))
                fail("Expected installer URL to be rejected: $target")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `selects documented Alpine apk repository`() {
        val option = requireNotNull(linuxInstallerOption(listOf("ID=alpine", "VERSION_ID=3.20")))

        assertEquals("Alpine Linux", option.distribution)
        assertTrue(option.command.contains("sudo apk add doppler"))
    }

    @Test
    fun `selects apt for documented Debian and Ubuntu versions`() {
        val debian = requireNotNull(linuxInstallerOption(listOf("ID=debian", "VERSION_ID=11")))
        val ubuntu = requireNotNull(linuxInstallerOption(listOf("ID=ubuntu", "VERSION_ID=22.04")))

        assertEquals("Debian", debian.distribution)
        assertTrue(debian.command.contains("apt-get install -y doppler"))
        assertTrue(debian.command.contains("gpg --batch --yes --dearmor"))
        assertEquals("Ubuntu", ubuntu.distribution)
        assertTrue(ubuntu.command.contains("apt-get install -y doppler"))
    }

    @Test
    fun `selects yum for documented Red Hat family distributions`() {
        listOf("rhel", "centos", "amzn").forEach { distribution ->
            val option = requireNotNull(linuxInstallerOption(listOf("ID=$distribution", "VERSION_ID=9")))
            assertTrue(option.command.contains("sudo yum install -y doppler"))
        }
    }

    @Test
    fun `uses shell fallback for unsupported and old distributions`() {
        assertNull(linuxInstallerOption(listOf("ID=fedora", "VERSION_ID=40")))
        assertNull(linuxInstallerOption(listOf("ID=ubuntu", "VERSION_ID=20.04")))
        assertNull(linuxInstallerOption(listOf("ID=debian", "VERSION_ID=10")))
    }

    @Test
    fun `reviewed installer is copied to a privileged file and verified before execution`() {
        val installer = ReviewedInstaller(Path.of("/tmp/reviewed installer.sh"), "a".repeat(64))
        val command = reviewedInstallerCommand(installer, InstallerScope.SYSTEM)

        assertTrue(command.startsWith("sudo sh -c "))
        assertTrue(command.contains("mktemp /tmp/doppler-installer.XXXXXX"))
        val trapLine = command.substringAfter("trap ").substringBefore('\n')
        assertTrue(trapLine.endsWith(" 0"))
        assertFalse(trapLine.contains("EXIT"))
        assertTrue(command.contains("cat -- \"\$source_path\" > \"\$root_copy\""))
        assertTrue(command.contains("sha256sum \"\$root_copy\""))
        assertTrue(command.contains("sh \"\$root_copy\""))
        assertFalse(command.contains("--install-path"))
        assertTrue(command.contains("'/tmp/reviewed installer.sh'"))
        assertFalse(command.contains("sudo sh '/tmp/reviewed installer.sh'"))
    }

    @Test
    fun `reviewed installer defaults to a per-user install without sudo`() {
        val installer = ReviewedInstaller(Path.of("/tmp/reviewed installer.sh"), "a".repeat(64))
        val command = reviewedInstallerCommand(installer)

        assertEquals(command, reviewedInstallerCommand(installer, InstallerScope.USER))
        assertFalse(command.contains("sudo"))
        assertTrue(command.startsWith("sh -c "))
        assertTrue(command.contains("sha256sum \"\$root_copy\""))
        assertTrue(command.contains("install_dir=\"\$HOME/.local/bin\""))
        assertTrue(command.contains("mkdir -p -- \"\$install_dir\""))
        assertTrue(command.contains("sh \"\$root_copy\" --install-path \"\$install_dir\""))
        // The checksum must be verified before the installer runs.
        assertTrue(command.indexOf("SHA-256 mismatch") < command.indexOf("--install-path"))
    }

    @Test
    fun `reviewed installer command verifies the checksum and runs it in a real shell`() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        val home = Files.createTempDirectory("doppler-installer-home-")
        val installerPath = createInstallerTempFile()
        try {
            val script = "#!/bin/sh\nprintf '%s %s' \"\$1\" \"\$2\" > \"\$HOME/ran\"\n".toByteArray()
            Files.write(installerPath, script)
            val good = ReviewedInstaller(installerPath, installerSha256(script))

            assertEquals(0, runInShell(reviewedInstallerCommand(good), home))
            assertEquals("--install-path ${home.resolve(".local/bin")}", Files.readString(home.resolve("ran")))
            assertTrue(Files.isDirectory(home.resolve(".local/bin")))
            assertFalse("Installer file is removed after running", Files.exists(installerPath))

            Files.delete(home.resolve("ran"))
            Files.write(installerPath, script)
            val tampered = ReviewedInstaller(installerPath, "0".repeat(64))
            assertTrue(runInShell(reviewedInstallerCommand(tampered), home) != 0)
            assertFalse("Installer must not run on checksum mismatch", Files.exists(home.resolve("ran")))
        } finally {
            Files.deleteIfExists(installerPath)
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reviewed installer command rejects a malformed checksum`() {
        listOf("", "0000", "A".repeat(64), "a".repeat(63) + "'").forEach { sha ->
            try {
                reviewedInstallerCommand(ReviewedInstaller(Path.of("/tmp/doppler-installer-x.sh"), sha))
                fail("Expected malformed checksum to be rejected: $sha")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `installer download must be a shell script`() {
        requireShellScript("#!/bin/sh\necho ok\n".toByteArray())
        listOf("<!DOCTYPE html>", "#", "", "echo hi", "#x").forEach { body ->
            try {
                requireShellScript(body.toByteArray())
                fail("Expected non-script download to be rejected: $body")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `installer dialog choice maps to a scope or cancel`() {
        assertEquals(InstallerScope.USER, installerScopeForChoice(0))
        assertEquals(InstallerScope.SYSTEM, installerScopeForChoice(1))
        assertNull(installerScopeForChoice(2))
        assertNull(installerScopeForChoice(-1))
    }

    @Test
    fun `saved installer is owner read-only and records its digest`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val bytes = "#!/bin/sh\necho ok\n".toByteArray()
        val installer = saveReviewedInstaller(bytes)
        try {
            assertEquals(installerSha256(bytes), installer.sha256)
            assertTrue(bytes.contentEquals(Files.readAllBytes(installer.path)))
            assertEquals(setOf(PosixFilePermission.OWNER_READ), Files.getPosixFilePermissions(installer.path))
        } finally {
            deleteInstallerTempFile(installer.path)
        }
    }

    @Test
    fun `invalid installer downloads are rejected before a file is written`() {
        val tempDirectory = Path.of(System.getProperty("java.io.tmpdir"))

        fun installerFiles() =
            Files.list(tempDirectory).use { files ->
                files.filter { it.fileName.toString().startsWith("doppler-installer-") }.toList().toSet()
            }
        val before = installerFiles()
        mapOf(
            ByteArray(0) to "empty",
            "#!/bin/sh\n".toByteArray() to "too large",
            "<html>".toByteArray() to "not a shell script",
        ).forEach { (bytes, reason) ->
            try {
                saveReviewedInstaller(bytes, maxBytes = if (reason == "too large") 4 else MAX_INSTALLER_BYTES)
                fail("Expected installer to be rejected: $reason")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains(reason))
            }
        }
        assertEquals(before, installerFiles())
    }

    private fun runInShell(
        command: String,
        home: Path,
    ): Int {
        val process =
            ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .apply { environment()["HOME"] = home.toString() }
                .start()
        process.inputStream.readAllBytes()
        return process.waitFor()
    }

    @Test
    fun `installer digest is SHA-256 of downloaded bytes`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            installerSha256("abc".toByteArray()),
        )
    }

    @Test
    fun `installer cleanup deletes only plugin-created temporary paths`() {
        val installer = createInstallerTempFile()
        assertTrue(Files.exists(installer))

        assertTrue(deleteInstallerTempFile(installer))
        assertFalse(Files.exists(installer))
    }

    @Test
    fun `installer cleanup rejects paths outside its temporary file boundary`() {
        val unrelated = Files.createTempFile("unrelated-", ".sh")
        try {
            try {
                deleteInstallerTempFile(unrelated)
                fail("Expected unrelated path cleanup to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: remote data can never expand cleanup beyond plugin-created temp files.
            }
            assertTrue(Files.exists(unrelated))
        } finally {
            Files.deleteIfExists(unrelated)
        }
    }

    @Test
    fun `installer cleanup rejects a normalized traversal outside the temp directory`() {
        val tempDirectory = Path.of(System.getProperty("java.io.tmpdir"))
        val traversal =
            tempDirectory
                .resolve("nested")
                .resolve("..")
                .resolve("..")
                .resolve("doppler-installer-victim.sh")

        try {
            deleteInstallerTempFile(traversal)
            fail("Expected path traversal cleanup to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
