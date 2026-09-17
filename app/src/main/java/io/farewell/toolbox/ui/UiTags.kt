package io.farewell.toolbox.ui

/**
 * The machine-readable map of the UI.
 *
 * An LLM (or a UI test) driving this app should never have to guess an element
 * from its label text: labels get rewritten, and icons with
 * `contentDescription = null` are invisible to automation altogether. Every
 * interactive element and every piece of state worth reading carries one of
 * these tags, so this object is effectively the UI's public API — read it to
 * learn what the app can do, then find each element by its stable tag.
 *
 * Naming: `<screen>.<thing>`, so `patch.build` is the button that builds the
 * patch on the Patch screen.
 */
object UiTags {

    // Bottom navigation.
    const val TAB_PATCH = "tab.patch"
    const val TAB_RULES = "tab.rules"
    const val TAB_DATA = "tab.data"
    const val TAB_SETTINGS = "tab.settings"

    // Screen containers — assert which screen is currently on top.
    const val SCREEN_PATCH = "screen.patch"
    const val SCREEN_RULES = "screen.rules"
    const val SCREEN_DATA = "screen.data"
    const val SCREEN_SETTINGS = "screen.settings"

    // Patch screen — actions.
    const val PATCH_REFRESH = "patch.refresh"
    const val PATCH_BUILD = "patch.build"
    const val PATCH_EXPORT_ZIP = "patch.export.zip"
    const val PATCH_EXPORT_BACKUP = "patch.export.backup"
    const val PATCH_EXPORT_SEED = "patch.export.seed"
    const val PATCH_EXPORT_RESTORE = "patch.export.restore"
    const val PATCH_REBOOT = "patch.reboot"

    // Patch screen — readable state (drive the flow, then read these).
    const val PATCH_STATUS = "patch.status"
    const val PATCH_PROGRESS = "patch.progress"

    // Data screen.
    const val DATA_SYNC = "data.sync"
    const val DATA_SYNC_RETRY = "data.sync.retry"
    const val DATA_APPLY_PROPS = "data.apply.props"
    const val DATA_REFRESH = "data.refresh"

    // Rules screen — screen-level actions.
    const val RULES_APPLY = "rules.apply"
    const val RULES_CLEAR = "rules.clear"

    // Settings screen.
    const val SETTINGS_APPLY = "settings.apply"
    const val SETTINGS_REFRESH_PI = "settings.refresh.playintegrity"
    const val SETTINGS_PICK_KEYBOX = "settings.keybox.pick"
    const val SETTINGS_EXPORT_PROPS = "settings.props.export"
    const val SETTINGS_VERIFY_KEYBOX = "settings.keybox.verify"
    const val SETTINGS_KEYBOX_HEALTHIEST = "settings.keybox.healthiest"
    const val SETTINGS_KEYBOX_NEXT = "settings.keybox.next"
    const val SETTINGS_CHECK_READINESS = "settings.readiness.check"
    const val SETTINGS_AUTO_REFRESH = "settings.autorefresh"
    const val SETTINGS_AUTO_REFRESH_NOW = "settings.autorefresh.now"
    const val SETTINGS_VERDICT_BASIC = "settings.verdict.basic"
    const val SETTINGS_VERDICT_DEVICE = "settings.verdict.device"
    const val SETTINGS_VERDICT_STRONG = "settings.verdict.strong"
    const val SETTINGS_VERDICT_ANALYZE = "settings.verdict.analyze"
    const val SETTINGS_VERDICT_JSON = "settings.verdict.json"
    const val SETTINGS_VERDICT_JSON_ANALYZE = "settings.verdict.json.analyze"
    const val SETTINGS_REFRESH = "settings.refresh"
    const val SETTINGS_REBOOT = "settings.reboot"
}
