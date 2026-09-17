package android.security.keystore2;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class HookConfig {

    public static final String KEY_CONFIG = "sys_keystore_cfg";
    public static final String KEY_KEYBOX = "sys_keybox_cfg";
    public static final String KEY_PROBE_NONCE = "sys_probe_nonce";
    public static final String KEY_PROBE = "sys_probe_key";

    private static final HookConfig EMPTY = new HookConfig(new JSONObject());

    private final JSONObject root;

    private HookConfig(JSONObject root) {
        this.root = root;
    }

    public static HookConfig empty() {
        return EMPTY;
    }

    public static HookConfig parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            // Nothing pushed to the device yet — the most common reason a spoof
            // does nothing, so make sure it is visible instead of silent.
            HookDebug.logMissing();
            return EMPTY;
        }
        try {
            HookConfig config = new HookConfig(new JSONObject(HookCodec.decode(raw)));
            // One state dump per config change, verbose-gated: `setprop
            // log.tag.KeyStoreHooks DEBUG` + `adb logcat -s KeyStoreHooks`.
            HookDebug.logOnChange(raw, config);
            return config;
        } catch (Throwable throwable) {
            HookLog.e("bad config json", throwable);
            return EMPTY;
        }
    }

    public static HookConfig load(ContentResolver resolver) {
        if (resolver == null) {
            return parse(ConfigFile.read(new java.io.File(ConfigFile.KEYSTORE)));
        }
        try {
            String raw = Settings.Global.getString(resolver, KEY_CONFIG);
            if (raw == null || raw.isEmpty()) {
                // Rootless: the patch zip wrote the blob where any process can
                // read it. Settings wins when it exists, so a rooted user keeps
                // live config updates.
                raw = ConfigFile.read(new java.io.File(ConfigFile.KEYSTORE));
            }
            return parse(raw);
        } catch (Throwable throwable) {
            HookLog.e("config read failed", throwable);
            return parse(ConfigFile.read(new java.io.File(ConfigFile.KEYSTORE)));
        }
    }

    public boolean isSecureFlag() {
        return flag("secure_flag");
    }

    public boolean isHideDevStatus() {
        return flag("hide_dev_status");
    }

    public boolean isHideAppList() {
        return flag("hide_app_list");
    }

    private boolean flag(String name) {
        JSONObject flags = root.optJSONObject("flags");
        return flags != null && flags.optBoolean(name, false);
    }

    public Boolean featureState(String feature) {
        if (feature == null) {
            return null;
        }
        JSONObject features = root.optJSONObject("features");
        if (features == null || !features.has(feature)) {
            return null;
        }
        return features.optBoolean(feature, false);
    }

    public String installerOverride(String packageName) {
        if (packageName == null) {
            return null;
        }
        JSONObject installer = root.optJSONObject("installer");
        if (installer == null) {
            return null;
        }
        String value = installer.optString(packageName, "");
        return value.isEmpty() ? null : value;
    }

    public String propOverride(String packageName, String key) {
        JSONObject props = root.optJSONObject("props");
        if (props == null || key == null) {
            return null;
        }
        String value = null;
        JSONObject global = props.optJSONObject("*");
        if (global != null && global.has(key)) {
            value = global.optString(key, null);
        }
        if (packageName != null) {
            JSONObject perApp = props.optJSONObject(packageName);
            if (perApp != null && perApp.has(key)) {
                value = perApp.optString(key, null);
            }
        }
        return value;
    }

    public boolean isKeyboxSpoof() {
        return flag("keybox_spoof");
    }

    /**
     * Whether attestation may be forged for [packageName].
     *
     * Forging is opt-in per target: the app writes a `build` entry for every
     * package that should see the spoofed identity (the GMS stack, Play Store
     * and the key-attestation checkers), and nothing else. An ungated forger is
     * exactly what PIF Detector's active probe catches by requesting
     * `PURPOSE_ATTEST_KEY` from an unrelated app: without a gate it forges, and
     * the answer contradicts itself. With the gate, a non-target app gets the
     * genuine Keystore path, so an unprivileged detector has nothing to
     * analyse.
     */
    public boolean isAttestTarget(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        JSONObject build = root.optJSONObject("build");
        return build != null && build.has(packageName);
    }

    public String securityPatch(String packageName) {
        String value = propOverride(packageName, "ro.build.version.security_patch");
        if (value != null && !value.isEmpty()) {
            return value;
        }
        JSONObject build = buildOverride(packageName);
        if (build != null) {
            String patch = build.optString("SECURITY_PATCH", null);
            if (patch != null && !patch.isEmpty()) {
                return patch;
            }
        }
        return null;
    }

    public JSONObject buildOverride(String packageName) {
        JSONObject build = root.optJSONObject("build");
        if (build == null) {
            return null;
        }
        JSONObject merged = new JSONObject();
        boolean found = false;
        JSONObject global = build.optJSONObject("*");
        if (global != null) {
            copyInto(global, merged);
            found = true;
        }
        if (packageName != null) {
            JSONObject perApp = build.optJSONObject(packageName);
            if (perApp != null) {
                copyInto(perApp, merged);
                found = true;
            }
        }
        return found ? merged : null;
    }

    private static void copyInto(JSONObject source, JSONObject target) {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                target.put(key, source.get(key));
            } catch (Throwable ignored) {
            }
        }
    }

    public String settingValue(String packageName, String namespace, String name) {
        if (packageName == null || namespace == null || name == null) {
            return null;
        }
        JSONObject settings = root.optJSONObject("settings");
        JSONObject apps = settings == null ? null : settings.optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(packageName);
        JSONObject table = app == null ? null : app.optJSONObject(namespace);
        if (table == null || !table.has(name)) {
            return null;
        }
        return table.optString(name, null);
    }

    public boolean shouldRemove(String namespace, String name) {
        if (namespace == null || name == null) {
            return false;
        }
        JSONObject remove = root.optJSONObject("remove");
        if (remove == null) {
            return false;
        }
        Set<String> keys = namespaceSet(remove, namespace);
        return keys.contains(name);
    }

    private static Set<String> namespaceSet(JSONObject root, String namespace) {
        if (!root.has(namespace)) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        try {
            org.json.JSONArray array = root.getJSONArray(namespace);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        } catch (Throwable throwable) {
            HookLog.e("bad remove list for " + namespace, throwable);
        }
        return result;
    }
}
