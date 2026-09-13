package android.security.farewell;

import android.content.Context;
import android.provider.Settings;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeyboxEngine {

    private static final Pattern CERT_PATTERN = Pattern.compile(
            "<Certificate>\\s*([^<]+?)\\s*</Certificate>", Pattern.DOTALL);

    private static volatile String sCachedXml;
    private static volatile Certificate[] sCachedChain;

    private KeyboxEngine() {
    }

    public static KeyPair generateKeyPair(Object spi, FarewellConfig config) {
        return null;
    }

    public static Certificate[] replaceChain(Certificate[] chain, FarewellConfig config) {
        if (config == null || !config.isKeyboxSpoof()) {
            return chain;
        }
        Context context = FarewellState.context();
        if (context == null) {
            return chain;
        }
        String xml = readKeybox(context);
        if (xml == null || xml.isEmpty()) {
            return chain;
        }
        if (xml.equals(sCachedXml) && sCachedChain != null) {
            return sCachedChain;
        }
        Certificate[] parsed = parseChain(xml);
        if (parsed == null || parsed.length == 0) {
            return chain;
        }
        sCachedXml = xml;
        sCachedChain = parsed;
        FarewellLog.d("keybox chain replaced (" + parsed.length + " certs)");
        return parsed;
    }

    private static String readKeybox(Context context) {
        FarewellState.beginInternal();
        try {
            return Settings.Global.getString(context.getContentResolver(), FarewellConfig.KEY_KEYBOX);
        } catch (Throwable throwable) {
            FarewellLog.e("keybox read", throwable);
            return null;
        } finally {
            FarewellState.endInternal();
        }
    }

    private static Certificate[] parseChain(String xml) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<Certificate> certificates = new ArrayList<>();
            Matcher matcher = CERT_PATTERN.matcher(xml);
            while (matcher.find()) {
                byte[] der = android.util.Base64.decode(matcher.group(1), android.util.Base64.DEFAULT);
                certificates.add(factory.generateCertificate(new ByteArrayInputStream(der)));
            }
            if (certificates.isEmpty()) {
                return null;
            }
            return certificates.toArray(new Certificate[0]);
        } catch (Throwable throwable) {
            FarewellLog.e("keybox parse", throwable);
            return null;
        }
    }
}
