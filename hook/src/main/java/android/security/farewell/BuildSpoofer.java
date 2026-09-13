package android.security.farewell;

import android.os.Build;

import org.json.JSONObject;

import java.lang.reflect.Field;

public final class BuildSpoofer {

    private static final String[] BUILD_FIELDS = {
            "BRAND",
            "BRAND_FOR_ATTESTATION",
            "DEVICE",
            "DEVICE_FOR_ATTESTATION",
            "FINGERPRINT",
            "HARDWARE",
            "ID",
            "MANUFACTURER",
            "MANUFACTURER_FOR_ATTESTATION",
            "MODEL",
            "MODEL_FOR_ATTESTATION",
            "PRODUCT",
            "PRODUCT_FOR_ATTESTATION",
            "TAGS",
            "TIME",
            "TYPE",
            "USER"
    };

    private static final String[] VERSION_FIELDS = {
            "RELEASE",
            "RELEASE_OR_CODENAME",
            "RELEASE_OR_PREVIEW_DISPLAY",
            "SECURITY_PATCH",
            "DEVICE_INITIAL_SDK_INT"
    };

    private BuildSpoofer() {
    }

    public static void apply(FarewellConfig config, String packageName) {
        if (config == null) {
            return;
        }
        JSONObject overrides = config.buildOverride(packageName);
        if (overrides == null || overrides.length() == 0) {
            return;
        }
        for (String field : BUILD_FIELDS) {
            setField(Build.class, field, overrides.optString(field, null));
        }
        for (String field : VERSION_FIELDS) {
            setField(Build.VERSION.class, field, overrides.optString(field, null));
        }
    }

    private static void setField(Class<?> owner, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            Field field = owner.getField(name);
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) {
                field.set(null, value);
            } else if (type == int.class) {
                field.set(null, Integer.parseInt(value.trim()));
            } else if (type == long.class) {
                field.set(null, Long.parseLong(value.trim()));
            }
        } catch (Throwable throwable) {
            FarewellLog.w("build field " + name + " not spoofed: " + throwable);
        }
    }
}
