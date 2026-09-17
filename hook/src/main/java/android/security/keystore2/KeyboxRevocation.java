package android.security.keystore2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device counterpart to the desktop {@code --verify-keybox}: asks Google's
 * attestation status list whether the spoofed keybox has been revoked, and if so
 * makes the hook serve the real device chain instead.
 *
 * This must never break the key path. The status list is fetched off the critical
 * path (a background thread, once it goes stale) and cached on disk, so the
 * synchronous check is a map lookup. If the list cannot be fetched or parsed the
 * check returns "not revoked" and the spoofed chain is served as before — a
 * transient network failure must not silently disable the user's setup.
 *
 * Parsing is done by hand rather than with {@code org.json}: the Android runtime
 * ships {@code org.json}, but its android.jar stub throws under the JVM test
 * runtime, so a manual parse keeps this class unit-testable without a device.
 */
final class KeyboxRevocation {

    private static final String STATUS_URL = "https://android.googleapis.com/attestation/status";
    private static final long MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L; // matches Google Cache-Control
    private static final String CACHE_FILE = "keybox-revocation.json";

    // Each entry is `{"<lowercase-hex serial>": {"status": "REVOKED", "reason": "..."}}`.
    // An entry without a `status` key is not a revocation, so it is skipped. The key
    // is matched case-insensitively and lowercased, so any casing normalises to the
    // lowercase hex a cert serial formats to.
    private static final Pattern ENTRY = Pattern.compile(
            "\"([0-9a-fA-F]+)\"\\s*:\\s*\\{[^}]*\"status\"\\s*:\\s*\"([^\"]*)\"");

    private final File mCacheDir;
    private volatile Map<String, String> mStatus;
    private final AtomicLong mLoadedAt = new AtomicLong(0);
    private final AtomicBoolean mRefreshing = new AtomicBoolean(false);

    KeyboxRevocation(File cacheDir) {
        mCacheDir = cacheDir;
    }

    /**
     * Synchronous, allocation-free check for the hot path. Returns false when the
     * status list is unknown (not yet loaded, or a fetch is in flight) so the
     * caller keeps serving the spoofed chain.
     */
    boolean isRevoked(Certificate[] chain) {
        return isRevoked(chain, mStatus);
    }

    /** Shared logic, also the seam the tests drive with an injected map. */
    static boolean isRevoked(Certificate[] chain, Map<String, String> status) {
        if (chain == null || status == null || status.isEmpty()) {
            return false;
        }
        for (Certificate certificate : chain) {
            if (!(certificate instanceof X509Certificate)) {
                continue;
            }
            String serial = ((X509Certificate) certificate).getSerialNumber().toString(16).toLowerCase();
            if (status.containsKey(serial)) {
                return true;
            }
        }
        return false;
    }

    /** Load the cached list if needed, and refresh it in the background if stale. */
    void ensureFresh() {
        if (mStatus == null) {
            Map<String, String> fromDisk = loadFromDisk();
            if (fromDisk != null) {
                mStatus = fromDisk;
            }
        }
        if (isStale() && mRefreshing.compareAndSet(false, true)) {
            Thread thread = new Thread(this::refresh, "farewell-keybox-revocation");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }
    }

    /** Background fetch + parse + cache. Any failure is swallowed: stay best-effort. */
    void refresh() {
        try {
            String json = fetch(STATUS_URL);
            Map<String, String> parsed = parseStatus(json);
            if (parsed != null) {
                mStatus = parsed;
                mLoadedAt.set(System.currentTimeMillis());
                saveToDisk(json);
            }
        } catch (Throwable throwable) {
            HookLog.e("keybox revocation refresh failed", throwable);
        } finally {
            mRefreshing.set(false);
        }
    }

    static Map<String, String> parseStatus(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return map;
        }
        try {
            Matcher matcher = ENTRY.matcher(json);
            while (matcher.find()) {
                map.put(matcher.group(1).toLowerCase(), matcher.group(2));
            }
        } catch (Throwable ignored) {
            // Malformed JSON yields an empty map rather than throwing; the caller
            // then keeps serving the spoofed chain.
        }
        return map;
    }

    private boolean isStale() {
        long loadedAt = mLoadedAt.get();
        return loadedAt == 0 || (System.currentTimeMillis() - loadedAt) > MAX_AGE_MILLIS;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setUseCaches(false);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, String> loadFromDisk() {
        File file = new File(mCacheDir, CACHE_FILE);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            mLoadedAt.set(file.lastModified());
            return parseStatus(new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void saveToDisk(String json) {
        try {
            File file = new File(mCacheDir, CACHE_FILE);
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Cache is a best-effort optimisation; a missing file just means a fetch next time.
        }
    }
}
