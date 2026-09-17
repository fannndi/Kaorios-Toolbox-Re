package io.farewell.patcher.integrity

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Audit of an attestation chain **we actually served** — the adversarial
 * counterpart of `KeyboxVerifier`, built from PIF Detector's checks so we fail
 * on our own terms before a detector does.
 *
 * Where `KeyboxVerifier` answers "is this keybox usable?", this answers "does
 * the chain we hand out survive a detector that analyses it?":
 *
 *  - `isSelfSignedSingleCert` — a chain of one self-signed certificate is forged.
 *  - `chainSignaturesBroken` — every link must verify.
 *  - `chainHasNonCaIssuer` — every certificate that issues another must be a CA.
 *  - `anchorsToGoogle` — the chain must terminate at a current Google root.
 *  - `challengeMismatch` — the attestation must echo the caller's challenge.
 *
 * It works on a PEM chain (leaf first), the format `adb` dumps and the hook
 * debug path produce, so a device session can be audited offline.
 */
data class AttestationAuditReport(
    val certCount: Int,
    val linksValid: Boolean,
    val issuersAreCa: Boolean,
    val anchored: Boolean,
    val rootFingerprint: String?,
    val challengeEchoed: Boolean?,
    val attestation: AttestationInfo?,
    val problems: List<String>,
    val warnings: List<String>,
) {
    fun lines(): List<String> {
        val output = mutableListOf<String>()
        output += "Certificates: $certCount"
        output += if (linksValid) "Chain links: OK" else "Chain links: BROKEN"
        output += if (issuersAreCa) "Issuers are CAs: OK" else "Issuers are CAs: NO"
        output += if (anchored) {
            "Anchored to Google root: OK (sha256=${rootFingerprint ?: "?"})"
        } else {
            "Anchored to Google root: NO"
        }
        challengeEchoed?.let { output += if (it) "Challenge echo: OK" else "Challenge echo: MISMATCH" }
        attestation?.let { info ->
            val level = if (info.attestationSecurityLevel == 1) "TEE" else "SOFTWARE"
            val boot = when (info.verifiedBootState) {
                0 -> "Verified"
                1 -> "SelfSigned"
                2 -> "Unverified"
                3 -> "Failed"
                else -> "unknown"
            }
            output += "Attestation: v${info.attestationVersion}, level=$level, boot=$boot, locked=${info.deviceLocked}"
            info.osPatchLevel?.let { output += "Patch levels: os=$it vendor=${info.vendorPatchLevel} boot=${info.bootPatchLevel}" }
            if (info.brand != null || info.model != null) {
                output += "Attested IDs: ${info.brand}/${info.device}/${info.product} ${info.manufacturer} ${info.model}"
            }
            if (info.applicationPackages.isNotEmpty()) {
                output += "Attested app: ${info.applicationPackages.joinToString()}"
            }
        } ?: run { output += "Attestation extension: absent" }
        problems.forEach { output += "PROBLEM: $it" }
        warnings.forEach { output += "WARN: $it" }
        return output
    }

    fun summary(): String = when {
        problems.isNotEmpty() -> "Attestation chain FAILS audit (${problems.size} problem(s))"
        warnings.isNotEmpty() -> "Attestation chain passes with ${warnings.size} warning(s)"
        else -> "Attestation chain passes audit"
    }
}

object AttestationAudit {

    private val PEM_PATTERN = Regex(
        "-----BEGIN CERTIFICATE-----\\s*(.+?)\\s*-----END CERTIFICATE-----",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parsePemChain(text: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return PEM_PATTERN.findAll(text).map { match ->
            val der = java.util.Base64.getMimeDecoder().decode(match.groupValues[1])
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    fun audit(
        chain: List<X509Certificate>,
        rootPems: List<String>,
        challenge: ByteArray? = null,
        expectedPackage: String? = null,
    ): AttestationAuditReport {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (chain.isEmpty()) {
            return AttestationAuditReport(
                certCount = 0, linksValid = false, issuersAreCa = false, anchored = false,
                rootFingerprint = null, challengeEchoed = null, attestation = null,
                problems = listOf("No certificates in the chain"), warnings = emptyList()
            )
        }
        if (chain.size == 1) {
            problems += "Single certificate chain: a self-signed certificate carrying an attestation extension is a forgery signature"
        }

        var linksValid = true
        for (index in 0 until chain.size - 1) {
            try {
                chain[index].verify(chain[index + 1].publicKey)
            } catch (throwable: Throwable) {
                linksValid = false
                problems += "Certificate $index is not signed by certificate ${index + 1}: ${throwable.message}"
            }
        }

        var issuersAreCa = true
        for (index in 1 until chain.size) {
            if (chain[index].basicConstraints < 0) {
                issuersAreCa = false
                problems += "Certificate $index issues another certificate but is not a CA (basicConstraints missing)"
            }
        }
        if (chain[0].basicConstraints >= 0) {
            warnings += "Leaf certificate is a CA; a genuine attested key is an end-entity certificate"
        }

        val roots = rootPems.mapNotNull { pem ->
            runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            }.getOrNull()
        }
        if (roots.isEmpty()) {
            problems += "No Google root certificates available to anchor against"
        }
        var anchored = false
        var rootFingerprint: String? = null
        val last = chain.last()
        for (root in roots) {
            val matches = try {
                last.encoded.contentEquals(root.encoded) || last.verify(root.publicKey).let { true }
            } catch (ignored: Throwable) {
                false
            }
            if (matches) {
                anchored = true
                rootFingerprint = fingerprint(root)
                break
            }
        }
        if (!anchored && roots.isNotEmpty()) {
            problems += "Chain does not reach a current Google attestation root"
        }

        val attestation = try {
            val wrapped = chain[0].getExtensionValue(AttestationParser.ATTESTATION_OID)
            if (wrapped == null) null else {
                val outer = DerReader(wrapped).read()
                AttestationParser.parse(wrapped.copyOfRange(outer.contentStart, outer.contentEnd))
            }
        } catch (throwable: Throwable) {
            null
        }
        if (attestation == null) {
            warnings += "Leaf has no key attestation extension"
        }

        var challengeEchoed: Boolean? = null
        if (attestation != null) {
            if (challenge != null) {
                challengeEchoed = attestation.challenge?.contentEquals(challenge) == true
                if (challengeEchoed == false) {
                    problems += "Attestation does not echo the caller's challenge (replayed or cached certificate)"
                }
            }
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
            if (expectedPackage != null && expectedPackage !in attestation.applicationPackages) {
                warnings += "Attested application id does not include $expectedPackage"
            }
        }

        return AttestationAuditReport(
            certCount = chain.size,
            linksValid = linksValid,
            issuersAreCa = issuersAreCa,
            anchored = anchored,
            rootFingerprint = rootFingerprint,
            challengeEchoed = challengeEchoed,
            attestation = attestation,
            problems = problems,
            warnings = warnings
        )
    }
}
