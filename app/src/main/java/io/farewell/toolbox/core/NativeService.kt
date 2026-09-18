package io.farewell.toolbox.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class NativeServiceInfo(
    val romDaemon: Boolean,
    val romConfig: Boolean,
    val status: String
)

/**
 * Native property helper.
 *
 * Stock mode (no ROM modifications): the farewelld binary is streamed to
 * /data/local/tmp/pfix via root and executed with --once, so ro.boot.* and
 * other write-once properties can be overridden without touching /system.
 *
 * ROM mode: when farewelld is baked into the ROM (/system/bin/farewelld +
 * init rc + SELinux rules), the persistent daemon does this automatically.
 */
object NativeService {

    const val STATUS_PROP = "sys.pfix_status"
    const val BINARY_PATH = "/system/bin/farewelld"
    const val CONFIG_PATH = "/system/etc/farewell/props.conf"

    private const val HELPER_ASSET = "farewelld"
    private const val HELPER_DIR = "/data/local/tmp/pfix"
    private const val HELPER_PATH = "$HELPER_DIR/farewelld"
    private const val HELPER_CONF = "$HELPER_DIR/props.conf"
    private const val PREFS = "farewell"
    private const val KEY_RESULT = "native_last"

    fun probe(): NativeServiceInfo = NativeServiceInfo(
        romDaemon = File(BINARY_PATH).exists(),
        romConfig = File(CONFIG_PATH).exists(),
        status = readProperty(STATUS_PROP)
    )

    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESULT, "").orEmpty()

    fun storeResult(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESULT, message)
            .apply()
    }

    fun statusSummary(context: Context): String {
        val info = probe()
        val mode = if (info.romDaemon) "ROM daemon installed" else "stock mode (root helper)"
        val romStatus = if (info.status.isNotEmpty()) "prop ${info.status}" else "prop not set"
        val last = lastResult(context).ifEmpty { "no run yet" }
        return "$mode | $romStatus | $last"
    }

    suspend fun applyStock(context: Context): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val outcome = runCatching {
            if (!RootShell.isRootAvailable()) {
                // The one layer that genuinely needs root in stock mode: it
                // writes the property areas directly. The rootless equivalent is
                // ROM mode (bake native/rom/ into the ROM); everything else in
                // this app works without root.
                error("no root: native layer needs stock-root or ROM mode (Java hook still covers target processes)")
            }
            val binary = context.assets.open(HELPER_ASSET).use { it.readBytes() }
            val config = PlayIntegritySetup.buildDaemonConfig(context)

            val install = RootShell.runWithStdin(
                "mkdir -p $HELPER_DIR && cat > $HELPER_PATH && chmod 0755 $HELPER_PATH && echo installed",
                binary,
                timeoutSeconds = 180
            )
            if (install.code != 0) {
                error("helper install failed: ${install.output.trim().takeLast(300)}")
            }

            val write = RootShell.runWithStdin(
                "cat > $HELPER_CONF && echo written",
                config.toByteArray(Charsets.UTF_8),
                timeoutSeconds = 60
            )
            if (write.code != 0) {
                error("config write failed: ${write.output.trim().takeLast(300)}")
            }

            val run = RootShell.run("$HELPER_PATH --once --config $HELPER_CONF --verbose", timeoutSeconds = 180)
            val summary = run.output.lineSequence()
                .lastOrNull { it.contains("pass:") }
                ?.substringAfter("farewelld: ")
                ?.trim()
            if (run.code != 0) {
                error("helper run failed (${run.code}): ${run.output.trim().takeLast(300)}")
            }
            val checks = listOf("ro.boot.verifiedbootstate" to "green", "ro.boot.flash.locked" to "1")
            val passed = checks.count { (name, expected) -> readProperty(name) == expected }
            "${summary ?: "applied"} | verified $passed/${checks.size}"
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000
        val message = outcome.getOrElse { "failed: ${it.message}" }
        val stamped = "$message (${elapsed}s)"
        storeResult(context, stamped)
        stamped
    }

    private fun readProperty(name: String): String = runCatching {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output
    }.getOrDefault("")
}
