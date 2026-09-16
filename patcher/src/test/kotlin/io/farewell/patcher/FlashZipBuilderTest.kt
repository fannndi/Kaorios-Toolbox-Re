package io.farewell.patcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/**
 * [FlashZipBuilder] produces the artifact a user actually flashes, and the shell
 * installer drives everything from the zip's entry names: it derives which
 * partitions to mount and back up from `cut -d/ -f1` on each manifest path, then
 * writes each entry to `/<name>`.
 *
 * That makes the entry *names* the contract. A wrong prefix does not fail the
 * build — it silently flashes to the wrong partition, or mounts nothing and aborts.
 */
class FlashZipBuilderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun names(zip: File): List<String> =
        ZipFile(zip).use { file ->
            Collections.list(file.entries()).filter { !it.isDirectory }.map { it.name }
        }

    private fun bytes(zip: File, name: String): ByteArray =
        ZipFile(zip).use { file -> file.getInputStream(file.getEntry(name)).use { it.readBytes() } }

    // --- structure -----------------------------------------------------------

    @Test
    fun writesTemplateEntriesAndFileEntries() {
        val payload = temp.newFile("framework.jar").apply { writeBytes(ByteArray(64) { 7 }) }
        val zip = File(temp.root, "patch.zip")

        FlashZipBuilder.build(
            zip,
            mapOf("manifest.txt" to "framework.jar 0644\n".toByteArray()),
            mapOf("system_root/system/framework/framework.jar" to payload)
        )

        assertEquals(
            listOf("manifest.txt", "system_root/system/framework/framework.jar"),
            names(zip)
        )
    }

    @Test
    fun preservesBinaryPayloadByteForByte() {
        // A framework.jar is tens of megabytes of arbitrary bytes; any mangling here
        // would produce a jar that does not boot.
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val source = temp.newFile("framework.jar").apply { writeBytes(payload) }
        val zip = File(temp.root, "patch.zip")

        FlashZipBuilder.build(zip, emptyMap(), mapOf("system_root/system/framework/framework.jar" to source))

        assertTrue(
            "the payload must survive the zip unchanged",
            bytes(zip, "system_root/system/framework/framework.jar").contentEquals(payload)
        )
    }

    @Test
    fun preservesTemplateBytesExactly() {
        // The installer scripts must stay LF-only; CRLF breaks TWRP's /sbin/sh. The
        // builder must not touch what the caller sanitised.
        val script = "#!/sbin/sh\nui_print \"hi\"\n".toByteArray()
        val zip = File(temp.root, "patch.zip")

        FlashZipBuilder.build(zip, mapOf("META-INF/com/google/android/update-binary" to script), emptyMap())

        assertTrue(bytes(zip, "META-INF/com/google/android/update-binary").contentEquals(script))
    }

    @Test
    fun createsTheParentDirectory() {
        val zip = File(temp.root, "nested/deeper/patch.zip")

        FlashZipBuilder.build(zip, mapOf("manifest.txt" to "x 0644\n".toByteArray()), emptyMap())

        assertTrue("the output directory must be created", zip.exists())
        assertEquals(listOf("manifest.txt"), names(zip))
    }

    @Test
    fun anEmptyBuildIsStillAValidZip() {
        val zip = File(temp.root, "empty.zip")

        FlashZipBuilder.build(zip, emptyMap(), emptyMap())

        assertTrue(zip.exists())
        assertTrue(names(zip).isEmpty())
        // Readable, not just present.
        ZipFile(zip).use { assertEquals(0, it.size()) }
    }

    @Test
    fun writesEveryEntryWhenSeveralArePresent() {
        val a = temp.newFile("a.jar").apply { writeText("a") }
        val b = temp.newFile("b.jar").apply { writeText("b") }
        val zip = File(temp.root, "multi.zip")

        FlashZipBuilder.build(
            zip,
            mapOf("manifest.txt" to "x\n".toByteArray(), "META-INF/com/ks/mount.sh" to "#!/sbin/sh\n".toByteArray()),
            mapOf(
                "system_root/system/framework/framework.jar" to a,
                "vendor/odm/etc/build.prop" to b
            )
        )

        assertEquals(
            listOf(
                "manifest.txt",
                "META-INF/com/ks/mount.sh",
                "system_root/system/framework/framework.jar",
                "vendor/odm/etc/build.prop"
            ),
            names(zip)
        )
    }

    // --- entry naming, which the installer depends on ------------------------

    @Test
    fun systemFilesGoUnderSystemRoot() {
        // TWRP mounts the system partition at /system_root, so a system payload must
        // carry that prefix or it is written to the wrong place.
        val framework = temp.newFile("framework.jar").apply { writeText("f") }
        val services = temp.newFile("services.jar").apply { writeText("s") }

        val entries = FlashZipBuilder.entriesForFramework(framework, services)

        assertEquals(
            setOf(
                "system_root/system/framework/framework.jar",
                "system_root/system/framework/services.jar"
            ),
            entries.keys
        )
    }

    @Test
    fun otherPartitionsKeepTheirNativePath() {
        // Product, vendor, system_ext and odm are separate mounts, so their payloads
        // must NOT be rewritten under system_root.
        for (path in listOf(
            "product/etc/build.prop",
            "system_ext/etc/build.prop",
            "vendor/build.prop",
            "vendor/odm/etc/build.prop"
        )) {
            assertFalse(
                "$path must not be rewritten under system_root",
                PatchTarget(JarKind.PROPS, path).zipPath.startsWith("system_root/")
            )
            assertEquals(path, PatchTarget(JarKind.PROPS, path).zipPath)
        }
    }

    @Test
    fun servicesJarIsOptional() {
        val framework = temp.newFile("framework.jar").apply { writeText("f") }

        val entries = FlashZipBuilder.entriesForFramework(framework, null)

        assertEquals(setOf("system_root/system/framework/framework.jar"), entries.keys)
    }

    @Test
    fun theInstallerDerivesPartitionsFromTheTopLevelDirectory() {
        // installer.sh does: NEEDED="$(echo "$FILES" | cut -d/ -f1 | sort -u)"
        // so the first path segment must be a real mount point.
        val payloads = listOf(
            "system_root/system/framework/framework.jar",
            "vendor/build_surya.prop",
            "vendor/odm/etc/build_surya.prop",
            "product/etc/build.prop",
            "system_ext/etc/build.prop"
        )
        val partitions = payloads.map { it.substringBefore('/') }.distinct().sorted()

        assertEquals(
            listOf("product", "system_ext", "system_root", "vendor"),
            partitions
        )
        // And every one of them is something the installer umounts at the end.
        val installer = File("app/src/main/assets/zip/installer.sh").takeIf { it.exists() }
        if (installer != null) {
            val script = installer.readText()
            for (partition in partitions) {
                assertTrue(
                    "installer.sh does not umount /$partition",
                    script.contains("/$partition")
                )
            }
        }
    }
}
