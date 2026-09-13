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

    fun summary(): String = "$jarKind: $appliedCount applied, $skippedCount skipped"
}

enum class JarKind {
    FRAMEWORK,
    SERVICES,
    SETTINGS_PROVIDER,
    MIUI_FRAMEWORK,
    MIUI_SERVICES,
    PROPS,
    OTHER
}
