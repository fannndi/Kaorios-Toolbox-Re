package io.farewell.toolbox.core

import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val output: String, val timedOut: Boolean = false)

object RootShell {

    fun isRootAvailable(): Boolean {
        val result = run("id", timeoutSeconds = 20)
        return result.code == 0 && result.output.contains("uid=0")
    }

    fun run(command: String, timeoutSeconds: Long = 120): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
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
            ShellResult(-1, throwable.message ?: "root shell failed")
        }
    }

    fun copyToFile(sourceCommand: String, destination: File, timeoutSeconds: Long = 600): ShellResult {
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            destination.delete()
        }
        val command = "{ $sourceCommand ; } > '${destination.absolutePath}'"
        return run(command, timeoutSeconds)
    }

    fun runWithStdin(command: String, input: ByteArray, timeoutSeconds: Long = 300): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
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
            ShellResult(-1, throwable.message ?: "root shell failed")
        }
    }
}
