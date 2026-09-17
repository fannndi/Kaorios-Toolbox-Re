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
    const val PATCH_REBOOT = "patch.reboot"

    // Patch screen — readable state (drive the flow, then read these).
    const val PATCH_STATUS = "patch.status"
    const val PATCH_PROGRESS = "patch.progress"
}
