package android.security.keystore2;

import java.security.MessageDigest;
import java.security.cert.Certificate;

/**
 * Recognises the current Google attestation roots on-device.
 *
 * Why this exists: a keybox can chain perfectly to a root that Google has
 * **retired**. The retired and current RSA roots share the same subject
 * (`serialNumber=f92009e853b6b045`), so every offline chain check passes while
 * the server rejects the attestation — the exact trap documented after the 2022
 * RSA root rotation. The revocation list does not help: "serial not revoked"
 * says nothing about the root being current.
 *
 * The two fingerprints below are the SHA-256 of the DER of the roots published
 * at `https://android.googleapis.com/attestation/root` (verified 2026-09-17).
 * A keybox whose root matches neither is still served — refusing it would
 * disable the spoof for a user with an unusual-but-valid chain, and Google is
 * the real judge — but the mismatch is logged and surfaced in the debug dump as
 * `keybox.anchor=retired`, which is the single fastest explanation for
 * "pipeline healthy, verdict BASIC/empty".
 */
final class KeyboxAnchor {

    /** Google hardware root RSA-4096 (valid to 2042-03-15). */
    static final String ROOT_RSA_SHA256 =
            "cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc";

    /** Key Attestation CA1, ECDSA P-384 (valid to 2035-07-15). */
    static final String ROOT_EC_SHA256 =
            "6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0";

    private KeyboxAnchor() {
    }

    /** Lowercase hex SHA-256 of a certificate's DER, or null when unavailable. */
    static String fingerprint(Certificate certificate) {
        if (certificate == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (Throwable throwable) {
            return null;
        }
    }

    static boolean isCurrentRoot(Certificate certificate) {
        String fingerprint = fingerprint(certificate);
        return ROOT_RSA_SHA256.equals(fingerprint) || ROOT_EC_SHA256.equals(fingerprint);
    }

    /**
     * Label for a served chain: the root is the last certificate.
     * `current`, `retired` (a root we know is not Google's current set) or
     * `unknown` when there is nothing to inspect.
     */
    static String label(Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return "unknown";
        }
        return isCurrentRoot(chain[chain.length - 1]) ? "current" : "retired";
    }
}
