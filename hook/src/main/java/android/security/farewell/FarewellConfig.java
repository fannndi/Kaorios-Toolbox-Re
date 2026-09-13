package android.security.farewell;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class FarewellConfig {

    public static final String KEY_CONFIG = "farewell_config";
    public static final String KEY_KEYBOX = "farewell_keybox";
    public static final String KEY_PROBE_NONCE = "farewell_probe_nonce";
    public static final String KEY_PROBE = "farewell_probe";

    private static final FarewellConfig EMPTY = new FarewellConfig(new JSONObject());

    private final JSONObject root;

    private FarewellConfig(JSONObject root) {
        this.root = root;
    }

    public static FarewellConfig empty() {
        return EMPTY;
    }

    public static FarewellConfig parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        try {
            return new FarewellConfig(new JSONObject(raw));
        } catch (Throwable throwable) {
            FarewellLog.e("bad config json", throwable);
            return EMPTY;
        }
    }

    public static FarewellConfig load(ContentResolver resolver) {
        if (resolver == null) {
            return EMPTY;
        }
        try {
            return parse(Settings.Global.getString(resolver, KEY_CONFIG));
        } catch (Throwable throwable) {
            FarewellLog.e("config read failed", throwable);
            return EMPTY;
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
            FarewellLog.e("bad remove list for " + namespace, throwable);
        }
        return result;
    }
}
