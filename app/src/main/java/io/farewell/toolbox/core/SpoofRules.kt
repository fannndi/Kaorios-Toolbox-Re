package io.farewell.toolbox.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-app spoof rules that the boot-classpath hook consumes from `sys_keystore_cfg`.
 *
 * The hook side (`HookConfig`) has always read these four sections; the app simply
 * never wrote them, which made installer-source spoofing and per-app Settings
 * spoofing inert — the patched call sites were wired but no config ever arrived.
 * This type is the missing writer.
 *
 * Exact shapes the hook expects (see `hook/.../HookConfig.java`):
 *
 * ```json
 * {
 *   "installer": { "<packageName>": "<installerPackage>" },
 *   "settings":  { "apps": { "<packageName>": { "<namespace>": { "<name>": "<value>" } } } },
 *   "remove":    { "<namespace>": ["<name>", ...] },
 *   "features":  { "<feature>": true }
 * }
 * ```
 *
 * - `installer` is keyed by the **calling** package, not the queried one:
 *   `PackageManagerInstallerRule` wires the one-argument `FILTER_INSTALLER(String)`
 *   overload, and `AppFilterSpoofer.filterInstallerPackageNameAuto` resolves the key
 *   through `HookState.packageForUid(callingUid())`. So a rule means "when *this*
 *   app asks for any package's installer, report this value instead".
 * - `settings` only ever changes what is returned to the reading app; the real
 *   setting is never written.
 * - `remove` is namespace-wide (not per app): a listed key reads as absent for
 *   every caller. Namespaces are the three real table names.
 * - `features` answers `PackageManager.hasSystemFeature` for the given feature
 *   string; `null` from the hook means "fall through to the stock value".
 */
data class SpoofRules(
    val installer: Map<String, String> = emptyMap(),
    val settings: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    val remove: Map<String, List<String>> = emptyMap(),
    val features: Map<String, Boolean> = emptyMap()
) {

    val isEmpty: Boolean
        get() = installer.isEmpty() && settings.isEmpty() && remove.isEmpty() && features.isEmpty()

    /** Count of individual rules, for the UI summary. */
    val ruleCount: Int
        get() = installer.size +
            settings.values.sumOf { tables -> tables.values.sumOf { it.size } } +
            remove.values.sumOf { it.size } +
            features.size

    fun toJson(): JSONObject {
        val root = JSONObject()

        if (installer.isNotEmpty()) {
            val obj = JSONObject()
            for ((pkg, value) in installer) {
                if (isPackageName(pkg) && value.isNotBlank()) {
                    obj.put(pkg, value.trim())
                }
            }
            if (obj.length() > 0) root.put("installer", obj)
        }

        if (settings.isNotEmpty()) {
            val apps = JSONObject()
            for ((pkg, tables) in settings) {
                if (!isPackageName(pkg)) continue
                val app = JSONObject()
                for ((namespace, entries) in tables) {
                    if (namespace !in NAMESPACES) continue
                    val table = JSONObject()
                    for ((name, value) in entries) {
                        if (name.isNotBlank()) table.put(name.trim(), value)
                    }
                    if (table.length() > 0) app.put(namespace, table)
                }
                if (app.length() > 0) apps.put(pkg, app)
            }
            if (apps.length() > 0) root.put("settings", JSONObject().put("apps", apps))
        }

        if (remove.isNotEmpty()) {
            val obj = JSONObject()
            for ((namespace, names) in remove) {
                if (namespace !in NAMESPACES) continue
                val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (cleaned.isNotEmpty()) {
                    obj.put(namespace, JSONArray().apply { cleaned.forEach { put(it) } })
                }
            }
            if (obj.length() > 0) root.put("remove", obj)
        }

        if (features.isNotEmpty()) {
            val obj = JSONObject()
            for ((feature, value) in features) {
                if (feature.isNotBlank()) obj.put(feature.trim(), value)
            }
            if (obj.length() > 0) root.put("features", obj)
        }

        return root
    }

    /** Merge into an existing config object, leaving every other section untouched. */
    fun applyTo(config: JSONObject): JSONObject {
        val json = toJson()
        for (key in json.keys()) {
            config.put(key, json.get(key))
        }
        return config
    }

    companion object {

        /** The three real Settings table names; the hook matches these exactly. */
        val NAMESPACES = listOf("global", "secure", "system")

        private const val FILE_NAME = "spoof-rules.json"

        /**
         * Permissive package check: at least two dot-separated segments, each
         * starting with a letter. Deliberately not a full Android package regex —
         * this only guards against typos reaching the hook config.
         */
        fun isPackageName(value: String): Boolean {
            if (value.isBlank() || value.length > 255) return false
            val segments = value.trim().split('.')
            if (segments.size < 2) return false
            return segments.all { segment ->
                segment.isNotEmpty() &&
                    segment.first().isLetter() &&
                    segment.all { it.isLetterOrDigit() || it == '_' }
            }
        }

        private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

        fun load(context: Context): SpoofRules {
            val target = file(context)
            if (!target.exists()) return SpoofRules()
            return try {
                fromJson(JSONObject(target.readText()))
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

        fun fromJson(root: JSONObject): SpoofRules {
            val installer = LinkedHashMap<String, String>()
            root.optJSONObject("installer")?.let { obj ->
                for (pkg in obj.keys()) {
                    val value = obj.optString(pkg, "")
                    if (value.isNotEmpty()) installer[pkg] = value
                }
            }

            val settings = LinkedHashMap<String, Map<String, Map<String, String>>>()
            root.optJSONObject("settings")
                ?.optJSONObject("apps")
                ?.let { apps ->
                    for (pkg in apps.keys()) {
                        val tables = LinkedHashMap<String, Map<String, String>>()
                        val app = apps.optJSONObject(pkg) ?: continue
                        for (namespace in app.keys()) {
                            val entries = LinkedHashMap<String, String>()
                            val table = app.optJSONObject(namespace) ?: continue
                            for (name in table.keys()) {
                                entries[name] = table.optString(name, "")
                            }
                            if (entries.isNotEmpty()) tables[namespace] = entries
                        }
                        if (tables.isNotEmpty()) settings[pkg] = tables
                    }
                }

            val remove = LinkedHashMap<String, List<String>>()
            root.optJSONObject("remove")?.let { obj ->
                for (namespace in obj.keys()) {
                    val array = obj.optJSONArray(namespace) ?: continue
                    val names = (0 until array.length()).mapNotNull { array.optString(it, null) }
                        .filter { it.isNotEmpty() }
                    if (names.isNotEmpty()) remove[namespace] = names
                }
            }

            val features = LinkedHashMap<String, Boolean>()
            root.optJSONObject("features")?.let { obj ->
                for (feature in obj.keys()) {
                    features[feature] = obj.optBoolean(feature, false)
                }
            }

            return SpoofRules(installer, settings, remove, features)
        }
    }
}
