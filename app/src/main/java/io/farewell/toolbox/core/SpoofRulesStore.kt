package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.SpoofRules
import org.json.JSONObject
import java.io.File

/**
 * Persistence for [SpoofRules].
 *
 * The rule model and its serialisation live in `:patcher` (`SpoofRules`), next to
 * the rest of the app↔hook protocol and covered by `SpoofRulesTest`. This object
 * only owns the file, so the app side stays a thin storage layer.
 *
 * Rules are stored as plain JSON on purpose: they are written into
 * `Settings.Global` on the next **Apply Play Integrity setup**, and being readable
 * makes a misconfigured rule obvious.
 */
object SpoofRulesStore {

    private const val FILE_NAME = "spoof-rules.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): SpoofRules {
        val target = file(context)
        if (!target.exists()) return SpoofRules()
        return try {
            SpoofRules.fromJson(JSONObject(target.readText()))
        } catch (throwable: Throwable) {
            SpoofRules()
        }
    }

    fun save(context: Context, rules: SpoofRules): Boolean = try {
        file(context).writeText(rules.toJson().toString(2))
        true
    } catch (throwable: Throwable) {
        false
    }

    fun exists(context: Context): Boolean = file(context).exists()
}
