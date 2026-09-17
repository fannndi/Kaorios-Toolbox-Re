package io.farewell.patcher.integrity

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class KeyboxReport(
    val chainValid: Boolean,
    val chainLength: Int,
    val rootSubject: String?,
    /** SHA-256 of the anchoring root, which disambiguates a rotated root that kept its subject. */
    val rootFingerprint: String?,
    val leafSerialHex: String?,
    val leafRevocation: String?,
    val softBanned: Boolean,
    val attestation: AttestationInfo?,
    val problems: List<String>,
    val warnings: List<String>
) {
    fun lines(): List<String> {
        val output = mutableListOf<String>()
        output += "Keybox certificates: $chainLength"
        output += if (chainValid) {
            "Chain to Google root: OK${rootSubject?.let { " ($it)" } ?: ""}"
        } else {
            "Chain to Google root: FAILED"
        }
        rootFingerprint?.let { output += "Root fingerprint: sha256=$it" }
        leafSerialHex?.let { output += "Leaf serial: $it" }
        output += "Revocation: ${leafRevocation ?: "not listed"}"
        attestation?.let { info ->
            val securityLevel = if (info.attestationSecurityLevel == 1) "TEE" else "SOFTWARE"
            val bootState = when (info.verifiedBootState) {
                0 -> "Verified"
                1 -> "SelfSigned"
                2 -> "Unverified"
                3 -> "Failed"
                else -> "unknown"
            }
            output += "Attestation: v${info.attestationVersion}, level=$securityLevel, boot=$bootState, locked=${info.deviceLocked}"
            info.osPatchLevel?.let { output += "Patch level: $it (os), ${info.vendorPatchLevel} (vendor), ${info.bootPatchLevel} (boot)" }
            if (info.brand != null || info.model != null) {
                output += "Attested IDs: ${info.brand}/${info.device}/${info.product} ${info.manufacturer} ${info.model}"
            }
            if (info.applicationPackages.isNotEmpty()) {
                output += "Attested app: ${info.applicationPackages.joinToString()}"
            }
        } ?: run { output += "Attestation extension: absent" }
        for (problem in problems) {
            output += "PROBLEM: $problem"
        }
        for (warning in warnings) {
            output += "WARN: $warning"
        }
        return output
    }

    fun summary(): String {
        val state = when {
            problems.isNotEmpty() -> "invalid"
            softBanned -> "soft-banned"
            leafRevocation != null -> "revoked"
            chainValid -> "valid"
            else -> "unknown"
        }
        return "Keybox $state (${chainLength} certs${rootSubject?.let { ", root=$it" } ?: ""})"
    }
}

object KeyboxVerifier {

    private val CERT_PATTERN = Regex("<Certificate>\\s*([^<]+?)\\s*</Certificate>", RegexOption.DOT_MATCHES_ALL)

    fun parseCertificates(xml: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = mutableListOf<X509Certificate>()
        for (match in CERT_PATTERN.findAll(xml)) {
            val der = java.util.Base64.getMimeDecoder().decode(match.groupValues[1])
            certificates += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
        return certificates
    }

    fun verify(
        xml: String,
        rootPems: List<String>,
        statuses: Map<String, IntegrityData.RevocationEntry>
    ): KeyboxReport {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val certificates = try {
            parseCertificates(xml)
        } catch (throwable: Throwable) {
            return KeyboxReport(
                chainValid = false,
                chainLength = 0,
                rootSubject = null,
                rootFingerprint = null,
                leafSerialHex = null,
                leafRevocation = null,
                softBanned = false,
                attestation = null,
                problems = listOf("Cannot parse keybox certificates: ${throwable.message}"),
                warnings = emptyList()
            )
        }

        if (certificates.isEmpty()) {
            return KeyboxReport(
                chainValid = false,
                chainLength = 0,
                rootSubject = null,
                rootFingerprint = null,
                leafSerialHex = null,
                leafRevocation = null,
                softBanned = false,
                attestation = null,
                problems = listOf("No certificates in keybox"),
                warnings = emptyList()
            )
        }

        val roots = rootPems.mapNotNull { pem ->
            runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            }.getOrNull()
        }
        if (roots.isEmpty()) {
            problems += "No Google root certificates available"
        }

        var chainValid = true
        for (index in 0 until certificates.size - 1) {
            try {
                certificates[index].verify(certificates[index + 1].publicKey)
            } catch (throwable: Throwable) {
                chainValid = false
                problems += "Certificate $index is not signed by certificate ${index + 1}: ${throwable.message}"
            }
        }

        var rootSubject: String? = null
        var rootFingerprint: String? = null
        val last = certificates.last()
        for (root in roots) {
            val anchored = try {
                if (last.encoded.contentEquals(root.encoded)) {
                    true
                } else {
                    last.verify(root.publicKey)
                    true
                }
            } catch (ignored: Throwable) {
                false
            }
            if (anchored) {
                rootSubject = root.subjectX500Principal.name
                rootFingerprint = AttestationAudit.fingerprint(root)
                break
            }
        }
        if (rootSubject == null) {
            chainValid = false
            problems += "Chain does not reach a known Google attestation root"
        }

        for ((index, certificate) in certificates.withIndex()) {
            try {
                certificate.checkValidity()
            } catch (throwable: Throwable) {
                warnings += "Certificate $index validity: ${throwable.message}"
            }
            if (index >= 1 && certificate.basicConstraints < 0) {
                // A detector treats a non-CA issuer as a hard failure
                // (`chainHasNonCaIssuer`), because it lets an attacker sign a
                // forged leaf with a genuine end-entity certificate and still
                // hand back a chain that verifies link by link.
                problems += "Certificate $index is used as issuer but is not a CA (basicConstraints missing)"
            }
        }

        val leafEntry = statusFor(certificates[0].serialNumber, statuses)
        val leafRevocation = leafEntry?.toString()
        if (leafRevocation != null) {
            if (leafEntry!!.status == "REVOKED") {
                problems += "Leaf certificate is listed in Google's status list: $leafRevocation"
            } else if (leafEntry.softBanned) {
                warnings += "Leaf certificate is SUSPENDED (soft-banned): $leafRevocation"
            } else {
                warnings += "Leaf certificate has status: $leafRevocation"
            }
        }
        for ((index, certificate) in certificates.withIndex()) {
            if (index == 0) continue
            val entry = statusFor(certificate.serialNumber, statuses)
            if (entry != null) {
                if (entry.status == "REVOKED") {
                    problems += "Certificate $index is revoked (${entry.reason ?: "no reason"})"
                } else if (entry.softBanned) {
                    warnings += "Certificate $index is SUSPENDED (soft-banned): $entry"
                } else {
                    warnings += "Certificate $index has status: $entry"
                }
            }
        }

        val attestation = extractAttestation(certificates[0])
        if (attestation == null) {
            warnings += "Leaf has no key attestation extension"
        } else {
            if (attestation.attestationSecurityLevel != 1) {
                warnings += "Attestation security level is not TrustedEnvironment"
            }
            if (attestation.verifiedBootState != null && attestation.verifiedBootState != 0) {
                warnings += "Attested verified boot state is not Verified"
            }
            if (attestation.deviceLocked == false) {
                warnings += "Attested device is not locked"
            }
            if (attestation.verifiedBootHashHex != null && attestation.verifiedBootHashHex.all { it == '0' }) {
                warnings += "Attested verified boot hash is all zeros"
            }
        }

        return KeyboxReport(
            chainValid = chainValid,
            chainLength = certificates.size,
            rootSubject = rootSubject,
            rootFingerprint = rootFingerprint,
            leafSerialHex = certificates[0].serialNumber.toString(16),
            leafRevocation = leafRevocation,
            softBanned = leafEntry?.softBanned == true,
            attestation = attestation,
            problems = problems,
            warnings = warnings
        )
    }

    /**
     * Look a certificate up in Google's status list.
     *
     * The list is keyed by the **certificate serial number in lowercase hex**, and
     * the published schema constrains the key to `^[a-f1-9][a-f0-9]*$` — no leading
     * zeros, never `0x` prefixed. Verified against the live list: all 1746 keys
     * match that pattern, and 976 of them consist only of digits `0-9`, so they look
     * decimal while actually being hex serials whose hex happens to contain no
     * `a-f`.
     *
     * The decimal form is deliberately **not** tried. It was the first lookup
     * previously, and it is the only variant that can resolve to a *different*
     * certificate: serial 10000 has decimal `"10000"` and hex `"2710"`, so a decimal
     * lookup could match the entry of a different key whose hex serial is `10000`
     * and report its status. Every remaining variant is the same serial in another
     * notation, so none of them can cross-match. `BigInteger.toString(16)` already
     * gives lowercase without leading zeros, matching the schema directly.
     */
    private fun statusFor(
        serial: BigInteger,
        statuses: Map<String, IntegrityData.RevocationEntry>
    ): IntegrityData.RevocationEntry? {
        val hex = serial.toString(16)
        return statuses[hex]
            ?: statuses[hex.uppercase()]
            ?: statuses["0x$hex"]
    }

    private fun extractAttestation(certificate: X509Certificate): AttestationInfo? {
        return try {
            val wrapped = certificate.getExtensionValue(AttestationParser.ATTESTATION_OID) ?: return null
            val outer = DerReader(wrapped).read()
            val keyDescription = wrapped.copyOfRange(outer.contentStart, outer.contentEnd)
            AttestationParser.parse(keyDescription)
        } catch (throwable: Throwable) {
            null
        }
    }
}
