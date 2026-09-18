package io.farewell.toolbox.core

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val output: String, val timedOut: Boolean = false)

object RootShell {

    // Signature|privileged permissions that the public SDK does not expose as
    // constants. Names must match the manifest and the privapp allowlist.
    private const val FORCE_STOP_PACKAGES = "android.permission.FORCE_STOP_PACKAGES"
    private const val CLEAR_APP_USER_DATA = "android.permission.CLEAR_APP_USER_DATA"

    /**
     * Runs a command with this app's own uid — no `su`.
     *
     * The interesting commands (`am force-stop`, `pm clear`, `settings put`) are
     * ordinary shell tools that check the *caller's* permissions, so a
     * privileged install (the system-app zip grants `FORCE_STOP_PACKAGES`,
     * `CLEAR_APP_USER_DATA` and `WRITE_SECURE_SETTINGS`) can run them directly.
     * An unprivileged app cannot, which is why the callers fall back to [run].
     */
    fun runDirect(command: String, timeoutSeconds: Long = 120): ShellResult =
        runProcess(listOf("/system/bin/sh", "-c", command), input = null, timeoutSeconds = timeoutSeconds)

    /** True when this install holds a signature|privileged permission. */
    fun isPrivileged(context: Context, permission: String): Boolean =
        context.applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Force-stops a package with no root when privileged, `su` when rooted, and
     * reports failure so callers can tell the user what to do instead.
     */
    fun forceStop(context: Context, packageName: String): Boolean {
        if (isPrivileged(context, FORCE_STOP_PACKAGES)) {
            val direct = runDirect("am force-stop $packageName", timeoutSeconds = 30)
            if (direct.code == 0) return true
        }
        val su = run("am force-stop $packageName", timeoutSeconds = 30)
        return su.code == 0
    }

    /** Clears a package's data with no root when privileged, else `su`. */
    fun clearData(context: Context, packageName: String): Boolean {
        if (isPrivileged(context, CLEAR_APP_USER_DATA)) {
            val direct = runDirect("pm clear $packageName", timeoutSeconds = 120)
            if (direct.code == 0 && !direct.output.contains("Failed")) return true
        }
        val su = run("pm clear $packageName", timeoutSeconds = 120)
        return su.code == 0
    }

    fun isRootAvailable(): Boolean {
        val result = run("id", timeoutSeconds = 20)
        return result.code == 0 && result.output.contains("uid=0")
    }

    fun run(command: String, timeoutSeconds: Long = 120): ShellResult =
        runProcess(listOf("su", "-c", command), input = null, timeoutSeconds = timeoutSeconds)

    fun copyToFile(sourceCommand: String, destination: File, timeoutSeconds: Long = 600): ShellResult {
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            destination.delete()
        }
        val command = "{ $sourceCommand ; } > '${destination.absolutePath}'"
        return run(command, timeoutSeconds)
    }

    fun runWithStdin(command: String, input: ByteArray, timeoutSeconds: Long = 300): ShellResult =
        runProcess(listOf("su", "-c", command), input = input, timeoutSeconds = timeoutSeconds)

    private fun runProcess(argv: List<String>, input: ByteArray?, timeoutSeconds: Long): ShellResult {
        return try {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
            if (input != null) {
                val writer = Thread {
                    try {
                        process.outputStream.use { stream ->
                            stream.write(input)
                            stream.flush()
                        }
                    } catch (_: Throwable) {
                    }
                }
                writer.isDaemon = true
                writer.start()
            }
            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val buffer = CharArray(8192)
            var read = reader.read(buffer)
            while (read >= 0) {
                output.append(buffer, 0, read)
                read = reader.read(buffer)
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ShellResult(-1, output.toString(), timedOut = true)
            } else {
                ShellResult(process.exitValue(), output.toString())
            }
        } catch (throwable: Throwable) {
            ShellResult(-1, throwable.message ?: "shell failed")
        }
    }
}
