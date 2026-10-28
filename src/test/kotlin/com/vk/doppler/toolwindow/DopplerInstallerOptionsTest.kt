package com.vk.doppler.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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
        val command = reviewedInstallerCommand(installer)

        assertTrue(command.contains("mktemp /tmp/doppler-installer.XXXXXX"))
        val trapLine = command.substringAfter("trap ").substringBefore('\n')
        assertTrue(trapLine.endsWith(" 0"))
        assertFalse(trapLine.contains("EXIT"))
        assertTrue(command.contains("cat -- \"\$source_path\" > \"\$root_copy\""))
        assertTrue(command.contains("sha256sum -c -"))
        assertTrue(command.contains("sh \"\$root_copy\""))
        assertTrue(command.contains("'/tmp/reviewed installer.sh'"))
        assertFalse(command.contains("sudo sh '/tmp/reviewed installer.sh'"))
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
