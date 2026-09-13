package io.farewell.patcher

object PropPatcher {

    data class Result(
        val content: String,
        val replaced: Int,
        val appended: Int
    )

    private val BLOCKED_PREFIXES = listOf(
        "ro.boot.",
        "ro.secureboot.",
        "ro.bootloader.state"
    )

    fun apply(input: String, props: Map<String, String>): Result {
        val newline = if (input.contains("\r\n")) "\r\n" else "\n"
        val lines = input.split(Regex("\r?\n")).toMutableList()
        val pending = props
            .filterKeys { key -> BLOCKED_PREFIXES.none { key.startsWith(it) } }
            .toMutableMap()
        var replaced = 0

        for (index in lines.indices) {
            val line = lines[index].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.contains("=")) continue
            val key = line.substringBefore("=").trim()
            val value = pending.remove(key) ?: continue
            lines[index] = "$key=$value"
            replaced++
        }

        var appended = 0
        for ((key, value) in pending) {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                lines.add("")
            }
            lines.add("$key=$value")
            appended++
        }

        return Result(lines.joinToString(newline), replaced, appended)
    }
}
