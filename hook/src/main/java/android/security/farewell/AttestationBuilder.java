package android.security.farewell;

import android.os.Build;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Random;

final class AttestationBuilder {

    private static final String ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";
    private static final String ECDSA_SHA256_OID = "1.2.840.10045.4.3.2";
    private static final String RSA_SHA256_OID = "1.2.840.113549.1.1.11";
    private static final String EC_PUBLIC_KEY_OID = "1.2.840.10045.2.1";
    private static final String P256_OID = "1.2.840.10045.3.1.7";

    private AttestationBuilder() {
    }

    static X509Certificate build(
            KeyPair keyPair,
            PrivateKey keyboxKey,
            boolean rsaKeybox,
            byte[] issuerDer,
            byte[] subjectDer,
            byte[] challenge,
            String packageName
    ) {
        try {
            ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
            byte[] x = toFixed(publicKey.getW().getAffineX(), 32);
            byte[] y = toFixed(publicKey.getW().getAffineY(), 32);
            byte[] point = Der.concat(new byte[]{0x04}, x, y);

            byte[] spki = Der.sequence(
                    Der.sequence(Der.oid(EC_PUBLIC_KEY_OID), Der.oid(P256_OID)),
                    Der.bitString(point)
            );

            long now = System.currentTimeMillis();
            long notBefore = now - 24L * 60 * 60 * 1000;
            long notAfter = now + 3650L * 24 * 60 * 60 * 1000;

            byte[] signatureAlgorithm = Der.sequence(Der.oid(rsaKeybox ? RSA_SHA256_OID : ECDSA_SHA256_OID));
            BigInteger serial = new BigInteger(63, new Random());

            byte[] keyDescription = keyDescription(challenge, packageName);

            byte[] extensions = Der.explicit(3, Der.sequence(
                    Der.sequence(
                            Der.oid(ATTESTATION_OID),
                            Der.octetString(keyDescription)
                    )
            ));

            byte[] tbs = Der.sequence(
                    Der.explicit(0, Der.integer(2)),
                    Der.integer(serial),
                    signatureAlgorithm,
                    Der.raw(issuerDer),
                    Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
                    Der.raw(subjectDer),
                    spki,
                    extensions
            );

            Signature signer = Signature.getInstance(rsaKeybox ? "SHA256withRSA" : "SHA256withECDSA");
            signer.initSign(keyboxKey);
            signer.update(tbs);
            byte[] signature = signer.sign();

            byte[] certificate = Der.sequence(tbs, signatureAlgorithm, Der.bitString(signature));

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(certificate));
        } catch (Throwable throwable) {
            FarewellLog.e("attestation build", throwable);
            return null;
        }
    }

    private static byte[] keyDescription(byte[] challenge, String packageName) {
        int patchLevel = patchLevel();

        byte[] softwareEnforced = Der.sequence(
                Der.explicit(1, Der.set(Der.integer(2))),
                Der.explicit(2, Der.integer(3)),
                Der.explicit(3, Der.integer(256)),
                Der.explicit(5, Der.set(Der.integer(4))),
                Der.explicit(10, Der.integer(1)),
                Der.explicit(701, Der.integer(System.currentTimeMillis() / 1000L)),
                Der.explicit(702, Der.integer(0))
        );

        byte[] teeEnforced = Der.sequence(
                Der.explicit(704, Der.sequence(
                        Der.enumerated(0),
                        Der.booleanValue(true),
                        Der.octetString(new byte[32])
                )),
                Der.explicit(705, Der.integer(Build.VERSION.SDK_INT)),
                Der.explicit(706, Der.integer(patchLevel)),
                Der.explicit(709, Der.octetString(attestationApplicationId(packageName))),
                Der.explicit(718, Der.integer(patchLevel)),
                Der.explicit(719, Der.integer(patchLevel))
        );

        return Der.sequence(
                Der.integer(4),
                Der.enumerated(1),
                Der.integer(4),
                Der.enumerated(1),
                Der.octetString(challenge != null ? challenge : new byte[0]),
                Der.octetString(new byte[0]),
                softwareEnforced,
                teeEnforced
        );
    }

    private static byte[] attestationApplicationId(String packageName) {
        byte[] digest = new byte[32];
        if (packageName == null) {
            packageName = "";
        }
        return Der.sequence(
                Der.set(Der.sequence(Der.octetString(packageName.getBytes()))),
                Der.set(Der.octetString(digest))
        );
    }

    private static int patchLevel() {
        try {
            String patch = Build.VERSION.SECURITY_PATCH;
            if (patch != null && patch.length() >= 7) {
                return Integer.parseInt(patch.substring(0, 4)) * 100
                        + Integer.parseInt(patch.substring(5, 7));
            }
        } catch (Throwable ignored) {
        }
        return 202401;
    }

    private static byte[] toFixed(BigInteger value, int size) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[size];
        if (bytes.length > size) {
            System.arraycopy(bytes, bytes.length - size, result, 0, size);
        } else {
            System.arraycopy(bytes, 0, result, size - bytes.length, bytes.length);
        }
        return result;
    }
}
