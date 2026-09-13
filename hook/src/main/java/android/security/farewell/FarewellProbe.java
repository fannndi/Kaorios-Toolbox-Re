package android.security.farewell;

import android.content.ContentResolver;
import android.provider.Settings;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class FarewellProbe {

    private static final ThreadLocal<Set<String>> REMOVE_STAGE = new ThreadLocal<>();

    private FarewellProbe() {
    }

    public static boolean isProbeName(String name) {
        return name != null && name.startsWith(FarewellConfig.KEY_PROBE);
    }

    public static void noteRemove(String namespace, String name) {
        if (!isProbeName(name) || namespace == null) {
            return;
        }
        Set<String> seen = REMOVE_STAGE.get();
        if (seen == null) {
            seen = new HashSet<>();
            REMOVE_STAGE.set(seen);
        }
        seen.add(namespace + "/" + name);
    }

    public static String answer(ContentResolver resolver, String namespace, String name) {
        String nonce = readNonce(resolver);
        if (nonce == null || nonce.isEmpty()) {
            return "";
        }
        Set<String> seen = REMOVE_STAGE.get();
        boolean removeSeen = seen != null && seen.contains(namespace + "/" + name);
        return digest(nonce + "|" + namespace + "|" + (removeSeen ? "R" : "-") + "|" + FarewellState.VERSION);
    }

    public static String expectedAnswer(String nonce, String namespace) {
        return digest(nonce + "|" + namespace + "|R|" + FarewellState.VERSION);
    }

    private static String readNonce(ContentResolver resolver) {
        if (resolver == null) {
            return null;
        }
        FarewellState.beginInternal();
        try {
            return Settings.Secure.getString(resolver, FarewellConfig.KEY_PROBE_NONCE);
        } catch (Throwable throwable) {
            FarewellLog.e("probe nonce read", throwable);
            return null;
        } finally {
            FarewellState.endInternal();
        }
    }

    private static String digest(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                builder.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                builder.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return builder.toString();
        } catch (Throwable throwable) {
            FarewellLog.e("probe digest", throwable);
            return "";
        }
    }
}
