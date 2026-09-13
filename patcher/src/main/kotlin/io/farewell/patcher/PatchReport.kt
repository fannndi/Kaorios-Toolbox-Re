package io.farewell.patcher

data class RuleOutcome(
    val rule: String,
    val target: String,
    val applied: Boolean,
    val detail: String
)

data class PatchReport(
    val jarKind: JarKind,
    val dexFiles: Int,
    val patchedClasses: Int,
    val outcomes: List<RuleOutcome>
) {
    val appliedCount: Int get() = outcomes.count { it.applied }
    val skippedCount: Int get() = outcomes.count { !it.applied }

    fun summary(): String {
        val builder = StringBuilder()
        builder.append(jarKind).append(": ")
            .append(appliedCount).append(" applied, ")
            .append(skippedCount).append(" skipped")
        return builder.toString()
    }
}

enum class JarKind {
    FRAMEWORK,
    SERVICES,
    OTHER
}
