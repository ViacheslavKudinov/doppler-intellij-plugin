package com.vk.doppler.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DopplerInstallerOptionsTest {
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
}
