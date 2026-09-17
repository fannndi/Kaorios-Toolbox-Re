package android.security.keystore2;

import java.io.File;
import java.io.FileInputStream;

/**
 * Flash-time config files, written by the patch zip into `/system/etc/farewell/`.
 *
 * This is the rootless channel: `settings put` needs shell or root, but the
 * installer copies these files into the system image without any runtime
 * privilege, and the hook falls back to them when the Settings row is empty.
 * The app writes the same `k2:` blobs into the zip that it would put into
 * Settings, so both channels carry identical bytes.
 *
 * Deliberately its own class, free of `org.json`: it is unit-testable on the
 * JVM, while [HookConfig] is not (its static initializer builds a JSONObject,
 * which is an android.jar stub off-device).
 */
final class ConfigFile {

    static final String DIR = "/system/etc/farewell";
    static final String KEYSTORE = DIR + "/keystore_cfg";
    static final String KEYBOX = DIR + "/keybox_cfg";

    private ConfigFile() {
    }

    /** Reads a provisioned config file, or null when absent/unreadable. */
    static String read(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) {
                return null;
            }
            byte[] bytes = new byte[(int) file.length()];
            FileInputStream input = new FileInputStream(file);
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count <= 0) {
                        break;
                    }
                    offset += count;
                }
                return new String(bytes, 0, offset, "UTF-8");
            } finally {
                input.close();
            }
        } catch (Throwable throwable) {
            return null;
        }
    }
}
