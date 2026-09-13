package io.farewell.patcher.integrity

data class AttestationInfo(
    val attestationVersion: Int,
    val attestationSecurityLevel: Int,
    val keymasterVersion: Int,
    val keymasterSecurityLevel: Int,
    val challengeLength: Int,
    val verifiedBootState: Int?,
    val deviceLocked: Boolean?,
    val verifiedBootHashHex: String?,
    val osVersion: Int?,
    val osPatchLevel: Int?,
    val vendorPatchLevel: Int?,
    val bootPatchLevel: Int?,
    val brand: String?,
    val device: String?,
    val product: String?,
    val manufacturer: String?,
    val model: String?,
    val applicationPackages: List<String>,
    val applicationDigests: List<String>
)

object AttestationParser {

    const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCHLEVEL = 706
    private const val TAG_ATTESTATION_APPLICATION_ID = 709
    private const val TAG_ATTESTATION_ID_BRAND = 710
    private const val TAG_ATTESTATION_ID_DEVICE = 711
    private const val TAG_ATTESTATION_ID_PRODUCT = 712
    private const val TAG_ATTESTATION_ID_MANUFACTURER = 716
    private const val TAG_ATTESTATION_ID_MODEL = 717
    private const val TAG_VENDOR_PATCHLEVEL = 718
    private const val TAG_BOOT_PATCHLEVEL = 719

    fun parse(keyDescription: ByteArray): AttestationInfo? {
        return try {
            val sequenceReader = DerReader(keyDescription)
            val sequence = sequenceReader.read()
            val reader = DerReader(keyDescription, sequence.contentStart, sequence.contentEnd)
            val values = reader.readAll()

            if (values.size < 8) {
                return null
            }

            var verifiedBootState: Int? = null
            var deviceLocked: Boolean? = null
            var verifiedBootHashHex: String? = null
            var osVersion: Int? = null
            var osPatchLevel: Int? = null
            var vendorPatchLevel: Int? = null
            var bootPatchLevel: Int? = null
            var brand: String? = null
            var device: String? = null
            var product: String? = null
            var manufacturer: String? = null
            var model: String? = null
            var applicationPackages = emptyList<String>()
            var applicationDigests = emptyList<String>()

            val teeReader = DerReader(keyDescription, values[7].contentStart, values[7].contentEnd)
            for (item in teeReader.readAll()) {
                val itemReader = DerReader(keyDescription, item.contentStart, item.contentEnd)
                val inner = itemReader.read()
                when (item.tagNumber) {
                    TAG_ROOT_OF_TRUST -> {
                        val rotReader = DerReader(keyDescription, inner.contentStart, inner.contentEnd)
                        val parts = rotReader.readAll()
                        if (parts.size >= 3) {
                            deviceLocked = rotReader.boolean(parts[1])
                            verifiedBootState = rotReader.enumerated(parts[2])
                        }
                        if (parts.size >= 4) {
                            verifiedBootHashHex = rotReader.content(parts[3]).toHex()
                        }
                    }
                    TAG_OS_VERSION -> osVersion = itemReader.intValue(inner)
                    TAG_OS_PATCHLEVEL -> osPatchLevel = itemReader.intValue(inner)
                    TAG_VENDOR_PATCHLEVEL -> vendorPatchLevel = itemReader.intValue(inner)
                    TAG_BOOT_PATCHLEVEL -> bootPatchLevel = itemReader.intValue(inner)
                    TAG_ATTESTATION_ID_BRAND -> brand = String(itemReader.content(inner))
                    TAG_ATTESTATION_ID_DEVICE -> device = String(itemReader.content(inner))
                    TAG_ATTESTATION_ID_PRODUCT -> product = String(itemReader.content(inner))
                    TAG_ATTESTATION_ID_MANUFACTURER -> manufacturer = String(itemReader.content(inner))
                    TAG_ATTESTATION_ID_MODEL -> model = String(itemReader.content(inner))
                    TAG_ATTESTATION_APPLICATION_ID -> {
                        val parsed = parseApplicationId(itemReader.content(inner))
                        applicationPackages = parsed.first
                        applicationDigests = parsed.second
                    }
                }
            }

            AttestationInfo(
                attestationVersion = reader.intValue(values[0]),
                attestationSecurityLevel = reader.enumerated(values[1]),
                keymasterVersion = reader.intValue(values[2]),
                keymasterSecurityLevel = reader.enumerated(values[3]),
                challengeLength = reader.content(values[4]).size,
                verifiedBootState = verifiedBootState,
                deviceLocked = deviceLocked,
                verifiedBootHashHex = verifiedBootHashHex,
                osVersion = osVersion,
                osPatchLevel = osPatchLevel,
                vendorPatchLevel = vendorPatchLevel,
                bootPatchLevel = bootPatchLevel,
                brand = brand,
                device = device,
                product = product,
                manufacturer = manufacturer,
                model = model,
                applicationPackages = applicationPackages,
                applicationDigests = applicationDigests
            )
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun parseApplicationId(der: ByteArray): Pair<List<String>, List<String>> {
        val packages = mutableListOf<String>()
        val digests = mutableListOf<String>()
        try {
            val reader = DerReader(der)
            val outer = reader.read()
            val outerReader = DerReader(der, outer.contentStart, outer.contentEnd)
            val sets = outerReader.readAll()
            if (sets.isNotEmpty()) {
                for (entry in DerReader(der, sets[0].contentStart, sets[0].contentEnd).readAll()) {
                    val entryReader = DerReader(der, entry.contentStart, entry.contentEnd)
                    val value = entryReader.read()
                    if (value.tagNumber == 4) {
                        packages += String(entryReader.content(value))
                    }
                }
            }
            if (sets.size > 1) {
                for (entry in DerReader(der, sets[1].contentStart, sets[1].contentEnd).readAll()) {
                    val entryReader = DerReader(der, entry.contentStart, entry.contentEnd)
                    digests += entryReader.content(entry).toHex()
                }
            }
        } catch (ignored: Throwable) {
        }
        return packages to digests
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

fun DerReader.readAll(): List<DerReader.Tlv> {
    val items = mutableListOf<DerReader.Tlv>()
    while (hasMore()) {
        val tlv = read()
        items += tlv
        advance(tlv)
    }
    return items
}
