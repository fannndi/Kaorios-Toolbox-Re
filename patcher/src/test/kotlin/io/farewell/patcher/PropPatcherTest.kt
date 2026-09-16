package io.farewell.patcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PropPatcher] is the only thing standing between a spoofed value and the
 * property file, and its behaviour is subtle: it replaces a key **in place** when
 * it already exists and appends new keys at **EOF**. That distinction is what made
 * the surya per-SKU import trap a real bug — see [replaceInPlaceLosesToLaterImport].
 */
class PropPatcherTest {

    @Test
    fun replacesExistingKeyInPlace() {
        val input = "ro.product.model=OLD\nro.build.type=user\n"
        val result = PropPatcher.apply(input, mapOf("ro.product.model" to "Pixel 8 Pro"))

        assertEquals(1, result.replaced)
        assertEquals(0, result.appended)
        assertTrue(result.content.contains("ro.product.model=Pixel 8 Pro"))
        // Position is preserved: the replaced line stays first.
        assertEquals("ro.product.model=Pixel 8 Pro", result.content.lineSequence().first())
    }

    @Test
    fun appendsMissingKeyAtEndOfFile() {
        val input = "ro.build.type=user\nro.build.tags=release-keys"
        val result = PropPatcher.apply(input, mapOf("ro.product.brand" to "google"))

        assertEquals(0, result.replaced)
        assertEquals(1, result.appended)
        assertEquals(
            "ro.product.brand=google",
            result.content.lineSequence().last()
        )
    }

    @Test
    fun appendsAfterAnImportDirective() {
        // The appended line lands after the import, so it wins at boot.
        val input = "ro.build.type=user\nimport /vendor/build_\${sku}.prop\n"
        val result = PropPatcher.apply(input, mapOf("ro.product.brand" to "google"))

        val lines = result.content.lines()
        val importIndex = lines.indexOfFirst { it.startsWith("import ") }
        val brandIndex = lines.indexOfFirst { it.startsWith("ro.product.brand=") }
        assertTrue("brand must be appended after the import", brandIndex > importIndex)
    }

    /**
     * The surya trap, pinned down as a regression test.
     *
     * Both `/vendor/build.prop` and `/vendor/odm/etc/build.prop` end with
     * `import .../build_${ro.boot.product.hardware.sku}.prop`, and that imported
     * file re-declares the identity keys. An in-place replacement therefore still
     * loses to the import — which is exactly why the SKU files have to be patched
     * as well. If this test ever starts failing because the patcher learned to
     * move keys, the SKU targets may no longer be necessary.
     */
    @Test
    fun replaceInPlaceLosesToLaterImport() {
        val input = buildString {
            append("ro.product.odm.model=M2007J20CG\n")
            append("import /vendor/odm/etc/build_\${sku}.prop\n")
        }
        val result = PropPatcher.apply(input, mapOf("ro.product.odm.model" to "Pixel 8 Pro"))

        assertEquals(1, result.replaced)
        val lines = result.content.lines()
        val modelIndex = lines.indexOfFirst { it.startsWith("ro.product.odm.model=") }
        val importIndex = lines.indexOfFirst { it.startsWith("import ") }
        assertEquals("Pixel 8 Pro", lines[modelIndex].substringAfter('='))
        assertTrue(
            "the in-place write sits BEFORE the import, so the import still wins",
            modelIndex < importIndex
        )
    }

    @Test
    fun preservesCrlfLineEndings() {
        val input = "ro.build.type=user\r\nro.build.tags=release-keys\r\n"
        val result = PropPatcher.apply(input, mapOf("ro.build.type" to "userdebug"))

        assertTrue(result.content.contains("\r\n"))
        assertFalse("must not introduce bare LF", result.content.contains(Regex("[^\r]\n")))
    }

    @Test
    fun blocksBootProperties() {
        val input = "ro.build.type=user\n"
        val result = PropPatcher.apply(
            input,
            mapOf(
                "ro.boot.verifiedbootstate" to "green",
                "ro.secureboot.lockstate" to "locked",
                "ro.bootloader.state" to "x"
            )
        )

        assertEquals(0, result.replaced)
        assertEquals(0, result.appended)
        assertFalse(result.content.contains("verifiedbootstate"))
        assertFalse(result.content.contains("secureboot"))
    }

    @Test
    fun skipsCommentsAndBlankLines() {
        val input = "# ro.product.model=COMMENTED\n\n   \nro.build.type=user\n"
        val result = PropPatcher.apply(input, mapOf("ro.product.model" to "Pixel 8 Pro"))

        assertEquals(0, result.replaced)
        assertEquals(1, result.appended)
        // The commented line is untouched; the new key goes to the end.
        assertTrue(result.content.contains("# ro.product.model=COMMENTED"))
    }

    @Test
    fun doesNotDuplicateAKeyAlreadyPresent() {
        val input = "ro.product.model=OLD\n"
        val result = PropPatcher.apply(input, mapOf("ro.product.model" to "NEW"))

        assertEquals(1, result.replaced)
        assertEquals(0, result.appended)
        assertEquals(1, result.content.lines().count { it.startsWith("ro.product.model=") })
    }

    @Test
    fun emptyMapIsANoOp() {
        val input = "ro.build.type=user\n"
        val result = PropPatcher.apply(input, emptyMap())

        assertEquals(0, result.replaced)
        assertEquals(0, result.appended)
        assertEquals(input.trimEnd('\n'), result.content.trimEnd('\n'))
    }

    @Test
    fun patchesMultipleKeysInOnePass() {
        val input = "ro.product.model=OLD\nro.build.type=OLD\n"
        val result = PropPatcher.apply(
            input,
            mapOf("ro.product.model" to "Pixel 8 Pro", "ro.build.type" to "user")
        )

        assertEquals(2, result.replaced)
        assertEquals(0, result.appended)
        assertTrue(result.content.contains("ro.product.model=Pixel 8 Pro"))
        assertTrue(result.content.contains("ro.build.type=user"))
    }
}
