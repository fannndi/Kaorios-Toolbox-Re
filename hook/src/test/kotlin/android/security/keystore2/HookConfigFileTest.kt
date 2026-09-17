package android.security.keystore2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files.createTempDirectory

/**
 * The rootless config channel: the patch zip drops the `k2:` blobs into
 * `/system/etc/farewell/` and the hook falls back to them when the Settings row
 * is empty — `settings put` needs shell or root, a file in the system image
 * needs neither. A bug in this reader silently disables the whole spoof on a
 * rootless device, so the paths and the read are pinned here.
 */
class HookConfigFileTest {

    private fun tempFile(contents: String?): File {
        val file = File(createTempDirectory("farewell-cfg").toFile(), "keystore_cfg")
        if (contents != null) file.writeText(contents)
        return file
    }

    @Test
    fun readsAProvisionedFile() {
        assertEquals("k2:abc", ConfigFile.read(tempFile("k2:abc")))
    }

    @Test
    fun missingOrEmptyFilesReadAsNullNotAsAnEmptyConfig() {
        assertNull(ConfigFile.read(File(tempFile("x").parentFile, "absent")))
        assertNull(ConfigFile.read(tempFile("")))
        assertNull(ConfigFile.read(null))
    }

    @Test
    fun thePathsMatchWhatTheInstallerWrites() {
        // installer.sh copies manifest entries to these exact system paths; the
        // app builds the zip with the same names. Keep all three in sync.
        assertEquals("/system/etc/farewell", ConfigFile.DIR)
        assertEquals("/system/etc/farewell/keystore_cfg", ConfigFile.KEYSTORE)
        assertEquals("/system/etc/farewell/keybox_cfg", ConfigFile.KEYBOX)
    }
}
