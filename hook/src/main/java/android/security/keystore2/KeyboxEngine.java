package android.security.keystore2;

import android.content.Context;
import android.provider.Settings;

import java.io.File;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeyboxEngine {

    private static final Pattern CERT_PATTERN = Pattern.compile(
            "<Certificate>\\s*([^<]+?)\\s*</Certificate>", Pattern.DOTALL);
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "<PrivateKey[^>]*>\\s*([^<]+?)\\s*</PrivateKey>", Pattern.DOTALL);

    private static volatile String sCachedXml;
    private static volatile Certificate[] sCachedChain;
    private static volatile Material sCachedMaterial;
    private static volatile KeyboxRevocation sRevocation;

    private static final ConcurrentHashMap<String, Entry> sGenerated = new ConcurrentHashMap<>();

    private KeyboxEngine() {
    }

    public static KeyPair generateKeyPair(Object spi, HookConfig config) {
        try {
            if (config == null || !config.isKeyboxSpoof()) {
                return null;
            }
            Context context = HookState.context();
            if (context == null) {
                return null;
            }
            String xml = readKeybox(context);
            if (xml == null || xml.isEmpty()) {
                return null;
            }
            Object spec = findKeyGenSpec(spi);
            if (spec == null) {
                return null;
            }
            String alias = aliasOf(spec);
            byte[] challenge = challengeOf(spec);
            if (alias == null || challenge == null || challenge.length == 0) {
                return null;
            }
            Material material = material(xml);
            if (material == null) {
                return null;
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair pair = generator.generateKeyPair();

            X509Certificate leaf = AttestationBuilder.build(
                    pair,
                    material.privateKey,
                    material.rsa,
                    material.subjectDer,
                    material.subjectDer,
                    challenge,
                    HookState.currentPackage(),
                    resolvePatchLevel(config, HookState.currentPackage()),
                    identity(HookState.currentPackage())
            );
            if (leaf == null) {
                return null;
            }

            Certificate[] chain = new Certificate[material.chain.length + 1];
            chain[0] = leaf;
            System.arraycopy(material.chain, 0, chain, 1, material.chain.length);
            sGenerated.put(alias, new Entry(pair, chain));
            HookLog.d("software keypair generated for alias " + alias + " (" + chain.length + " certs)");
            return pair;
        } catch (Throwable throwable) {
            HookLog.e("generateKeyPair", throwable);
            return null;
        }
    }

    public static Certificate[] chainForAlias(String alias) {
        if (alias == null) {
            return null;
        }
        Entry entry = sGenerated.get(alias);
        if (entry != null) {
            // If the keybox has been revoked, do not hand the app the spoofed chain.
            if (sRevocation != null && sRevocation.isRevoked(entry.chain)) {
                HookLog.w("keybox chain for alias " + alias + " is REVOKED by Google; not serving it");
                return null;
            }
            return entry.chain;
        }
        return null;
    }

    public static Certificate certificateForAlias(String alias) {
        Certificate[] chain = chainForAlias(alias);
        return chain != null && chain.length > 0 ? chain[0] : null;
    }

    /** Package-private: the current spoofed keybox chain, or null. Diagnostics only. */
    static Certificate[] cachedChain() {
        return sCachedChain;
    }

    /** Package-private: the live revocation checker, or null. Diagnostics only. */
    static KeyboxRevocation revocation() {
        return sRevocation;
    }

    /** Package-private: whether the cached keybox chain is on Google's revoked list. */
    static boolean isCachedChainRevoked() {
        return sRevocation != null && sCachedChain != null && sRevocation.isRevoked(sCachedChain);
    }

    public static Certificate[] replaceChain(Certificate[] chain, HookConfig config) {
        try {
            if (config == null || !config.isKeyboxSpoof()) {
                return chain;
            }
            Context context = HookState.context();
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
            if (sRevocation == null) {
                sRevocation = new KeyboxRevocation(new File(context.getFilesDir(), "revocation"));
            }
            sRevocation.ensureFresh();
            if (sRevocation.isRevoked(parsed)) {
                HookLog.w("keybox chain is REVOKED by Google; serving the real device chain instead");
                return chain;
            }
            sCachedXml = xml;
            sCachedChain = parsed;
            // Diagnostics: is the keybox loaded, and has Google revoked it?
            HookDebug.logKeybox(config);
            return parsed;
        } catch (Throwable throwable) {
            HookLog.e("replaceChain", throwable);
            return chain;
        }
    }

    private static AttestationBuilder.Identity identity(String packageName) {
        AttestationBuilder.Identity identity = new AttestationBuilder.Identity();
        try {
            identity.brand = android.os.Build.BRAND;
            identity.device = android.os.Build.DEVICE;
            identity.product = android.os.Build.PRODUCT;
            identity.manufacturer = android.os.Build.MANUFACTURER;
            identity.model = android.os.Build.MODEL;
            identity.bootHash = sha256((android.os.Build.FINGERPRINT + "|"
                    + android.os.Build.VERSION.SDK_INT + "|"
                    + android.os.Build.VERSION.SECURITY_PATCH).getBytes("UTF-8"));
            identity.signatureDigest = signatureDigest(packageName);
        } catch (Throwable throwable) {
            HookLog.e("identity", throwable);
        }
        return identity;
    }

    private static byte[] signatureDigest(String packageName) {
        HookState.beginInternal();
        try {
            android.content.Context context = HookState.context();
            if (context == null || packageName == null) {
                return null;
            }
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                    packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] signatures =
                    info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null;
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            return sha256(signatures[0].toByteArray());
        } catch (Throwable throwable) {
            return null;
        } finally {
            HookState.endInternal();
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static int resolvePatchLevel(HookConfig config, String packageName) {
        try {
            String value = config.securityPatch(packageName);
            if (value != null && value.length() >= 7) {
                return Integer.parseInt(value.substring(0, 4)) * 100
                        + Integer.parseInt(value.substring(5, 7));
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String readKeybox(Context context) {
        HookState.beginInternal();
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), HookConfig.KEY_KEYBOX);
            return HookCodec.decode(raw);
        } catch (Throwable throwable) {
            HookLog.e("keybox read", throwable);
            return null;
        } finally {
            HookState.endInternal();
        }
    }

    private static Object findKeyGenSpec(Object spi) {
        if (spi == null) {
            return null;
        }
        try {
            Class<?> type = spi.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(spi);
                        if (value instanceof android.security.keystore.KeyGenParameterSpec) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable throwable) {
            HookLog.e("findKeyGenSpec", throwable);
        }
        return null;
    }

    private static String aliasOf(Object spec) {
        try {
            return (String) spec.getClass().getMethod("getKeystoreAlias").invoke(spec);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static byte[] challengeOf(Object spec) {
        try {
            return (byte[]) spec.getClass().getMethod("getAttestationChallenge").invoke(spec);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Material material(String xml) {
        Material cached = sCachedMaterial;
        if (cached != null && xml.equals(cached.xml)) {
            return cached;
        }
        try {
            Matcher keyMatcher = KEY_PATTERN.matcher(xml);
            if (!keyMatcher.find()) {
                return null;
            }
            byte[] keyDer = android.util.Base64.decode(keyMatcher.group(1), android.util.Base64.DEFAULT);
            PrivateKey privateKey = parsePrivateKey(keyDer);
            if (privateKey == null) {
                HookLog.w("keybox private key could not be parsed");
                return null;
            }
            Certificate[] chain = parseChain(xml);
            if (chain == null || chain.length == 0) {
                return null;
            }
            boolean rsa = privateKey.getAlgorithm().equalsIgnoreCase("RSA");
            byte[] subject = ((X509Certificate) chain[0]).getSubjectX500Principal().getEncoded();
            Material material = new Material(xml, privateKey, rsa, subject, chain);
            sCachedMaterial = material;
            sCachedChain = chain;
            sCachedXml = xml;
            return material;
        } catch (Throwable throwable) {
            HookLog.e("keybox material", throwable);
            return null;
        }
    }

    private static PrivateKey parsePrivateKey(byte[] der) {
        for (String algorithm : new String[]{"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Throwable ignored) {
            }
        }
        return null;
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
            HookLog.e("keybox parse", throwable);
            return null;
        }
    }

    private static final class Material {
        final String xml;
        final PrivateKey privateKey;
        final boolean rsa;
        final byte[] subjectDer;
        final Certificate[] chain;

        Material(String xml, PrivateKey privateKey, boolean rsa, byte[] subjectDer, Certificate[] chain) {
            this.xml = xml;
            this.privateKey = privateKey;
            this.rsa = rsa;
            this.subjectDer = subjectDer;
            this.chain = chain;
        }
    }

    private static final class Entry {
        final KeyPair keyPair;
        final Certificate[] chain;

        Entry(KeyPair keyPair, Certificate[] chain) {
            this.keyPair = keyPair;
            this.chain = chain;
        }
    }
}
