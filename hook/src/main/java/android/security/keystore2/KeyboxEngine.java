package android.security.keystore2;

import android.content.Context;
import android.provider.Settings;

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
                    HookState.currentPackage()
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
        return entry != null ? entry.chain : null;
    }

    public static Certificate certificateForAlias(String alias) {
        Certificate[] chain = chainForAlias(alias);
        return chain != null && chain.length > 0 ? chain[0] : null;
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
            sCachedXml = xml;
            sCachedChain = parsed;
            return parsed;
        } catch (Throwable throwable) {
            HookLog.e("replaceChain", throwable);
            return chain;
        }
    }

    private static String readKeybox(Context context) {
        HookState.beginInternal();
        try {
            return Settings.Global.getString(context.getContentResolver(), HookConfig.KEY_KEYBOX);
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
