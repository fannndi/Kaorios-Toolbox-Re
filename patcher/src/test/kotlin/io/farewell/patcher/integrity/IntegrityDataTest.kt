package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [IntegrityData] feeds both the keybox verdict and the STRONG readiness report, so
 * a parsing slip here quietly weakens every revocation check. The fixtures use the
 * real response shapes, captured from
 * `https://android.googleapis.com/attestation/{root,status}`.
 */
class IntegrityDataTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun rootJson() = """
        [
          "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n",
          "-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----\n"
        ]
    """.trimIndent()

    private fun statusJson() = """
        {
          "entries": {
            "f1c172a699eaf51d": { "status": "REVOKED", "reason": "KEY_COMPROMISE" },
            "84a9d0297b0eb58ae7ff0e80de760605": {
              "status": "SUSPENDED", "reason": "SOFTWARE_FLAW",
              "comment": "Bug in keystore", "expires": "2026-12-31"
            },
            "12345678901234567890": { "status": "REVOKED" },
            "missingstatus": { "reason": "KEY_COMPROMISE" }
          }
        }
    """.trimIndent()

    private fun write(name: String, content: String): File =
        temp.newFile(name).apply { writeText(content) }

    // --- the cache contract --------------------------------------------------

    @Test
    fun maxAgeMatchesWhatGoogleAdvertises() {
        // Cache-Control: public, max-age=86400
        assertEquals(86_400_000L, IntegrityData.MAX_AGE_MILLIS)
    }

    @Test
    fun aMissingCacheIsStale() {
        assertTrue(IntegrityData.isStale(File(temp.root, "absent.json")))
    }

    @Test
    fun anEmptyCacheIsStale() {
        assertTrue(IntegrityData.isStale(write("empty.json", "")))
    }

    @Test
    fun aFreshCacheIsNotStale() {
        assertFalse(IntegrityData.isStale(write("fresh.json", rootJson())))
    }

    @Test
    fun aCacheOlderThanMaxAgeIsStale() {
        val file = write("old.json", rootJson())
        val now = System.currentTimeMillis()
        assertTrue(
            "a day-old status list must be refetched — new revocations appear weekly",
            IntegrityData.isStale(file, now = now + IntegrityData.MAX_AGE_MILLIS + 1)
        )
    }

    @Test
    fun aCacheJustInsideMaxAgeIsStillUsable() {
        val file = write("recent.json", rootJson())
        val now = System.currentTimeMillis()
        assertFalse(IntegrityData.isStale(file, now = now + IntegrityData.MAX_AGE_MILLIS - 1))
    }

    // --- parsing -------------------------------------------------------------

    @Test
    fun loadsRootsAndStatusesFromCacheFiles() {
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("status.json", statusJson()))

        assertEquals(2, snapshot.rootPems.size)
        assertTrue(snapshot.rootPems[0].startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(3, snapshot.statuses.size)
    }

    @Test
    fun parsesARevokedEntry() {
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("status.json", statusJson()))
        val entry = snapshot.statuses["f1c172a699eaf51d"]!!

        assertEquals("REVOKED", entry.status)
        assertEquals("KEY_COMPROMISE", entry.reason)
        assertFalse(entry.softBanned)
        assertNull(entry.expires)
    }

    @Test
    fun parsesASuspendedEntryWithItsOptionalFields() {
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("status.json", statusJson()))
        val entry = snapshot.statuses["84a9d0297b0eb58ae7ff0e80de760605"]!!

        assertEquals("SUSPENDED", entry.status)
        assertTrue(entry.softBanned)
        assertEquals("2026-12-31", entry.expires)
        assertEquals("Bug in keystore", entry.comment)
    }

    @Test
    fun acceptsAnEntryWithOnlyAStatus() {
        // Only `status` is required by Google's schema.
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("status.json", statusJson()))
        val entry = snapshot.statuses["12345678901234567890"]!!

        assertEquals("REVOKED", entry.status)
        assertNull(entry.reason)
    }

    @Test
    fun skipsAnEntryWithoutAStatus() {
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("status.json", statusJson()))

        assertFalse(
            "an entry with no status cannot mean anything",
            snapshot.statuses.containsKey("missingstatus")
        )
    }

    @Test
    fun upperCasesTheStatus() {
        val json = """{"entries": {"abc": {"status": "revoked"}}}"""
        val snapshot = IntegrityData.load(write("roots.json", rootJson()), write("lower.json", json))

        assertEquals("REVOKED", snapshot.statuses["abc"]!!.status)
    }

    @Test
    fun malformedJsonYieldsEmptyResultsRatherThanThrowing() {
        val snapshot = IntegrityData.load(
            write("roots.json", "not json"),
            write("status.json", "{ also not json")
        )

        assertTrue(snapshot.rootPems.isEmpty())
        assertTrue(snapshot.statuses.isEmpty())
    }

    @Test
    fun aStatusDocumentWithoutEntriesYieldsNothing() {
        val snapshot = IntegrityData.load(
            write("roots.json", rootJson()),
            write("status.json", """{"other": 1}""")
        )

        assertTrue(snapshot.statuses.isEmpty())
    }

    @Test
    fun aRootDocumentThatIsNotAnArrayYieldsNothing() {
        val snapshot = IntegrityData.load(
            write("roots.json", """{"roots": []}"""),
            write("status.json", statusJson())
        )

        assertTrue(snapshot.rootPems.isEmpty())
    }

    // --- entry rendering -----------------------------------------------------

    @Test
    fun rendersAnEntryForTheReport() {
        assertEquals(
            "REVOKED, KEY_COMPROMISE",
            IntegrityData.RevocationEntry("REVOKED", "KEY_COMPROMISE").toString()
        )
        assertEquals(
            "SUSPENDED, SOFTWARE_FLAW, expires 2026-12-31, Bug in keystore",
            IntegrityData.RevocationEntry(
                "SUSPENDED", "SOFTWARE_FLAW", "Bug in keystore", "2026-12-31"
            ).toString()
        )
        assertEquals("REVOKED", IntegrityData.RevocationEntry("REVOKED", null).toString())
    }

    @Test
    fun onlySuspendedCountsAsSoftBanned() {
        assertTrue(IntegrityData.RevocationEntry("SUSPENDED", null).softBanned)
        assertFalse(IntegrityData.RevocationEntry("REVOKED", null).softBanned)
    }

    @Test
    fun theSnapshotKeepsItsSourceFiles() {
        val roots = write("roots.json", rootJson())
        val status = write("status.json", statusJson())
        val snapshot = IntegrityData.load(roots, status)

        assertEquals(roots, snapshot.rootFile)
        assertEquals(status, snapshot.statusFile)
    }
}
