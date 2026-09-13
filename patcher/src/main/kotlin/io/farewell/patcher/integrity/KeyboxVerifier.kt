package io.farewell.patcher.integrity

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class KeyboxReport(
    val chainValid: Boolean,
    val chainLength: Int,
    val rootSubject: String?,
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
                warnings += "Certificate $index is not a CA but is used as issuer"
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
            leafSerialHex = certificates[0].serialNumber.toString(16),
            leafRevocation = leafRevocation,
            softBanned = leafEntry?.softBanned == true,
            attestation = attestation,
            problems = problems,
            warnings = warnings
        )
    }

    private fun statusFor(
        serial: BigInteger,
        statuses: Map<String, IntegrityData.RevocationEntry>
    ): IntegrityData.RevocationEntry? {
        val decimal = serial.toString(10)
        val hex = serial.toString(16)
        return statuses[decimal] ?: statuses[hex] ?: statuses[hex.uppercase()] ?: statuses["0x$hex"]
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
