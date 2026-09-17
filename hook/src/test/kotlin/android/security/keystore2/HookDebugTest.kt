package android.security.keystore2

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debugging used to be a dead end: `HookLog.VERBOSE` was hardcoded false, so every
 * debug line was discarded even with a device attached, and there was no single
 * place that showed whether a spoof had actually taken effect. These tests pin the
 * two halves of the fix — the structured dump (pure, so it is testable here) and
 * the verbose switch (forced via the test seam, since android.util.Log is a stub).
 */
class HookDebugTest {

    @After
    fun resetVerbose() {
        HookLog.setVerboseForTest(null)
    }

    @Test
    fun dumpExposesEveryFieldAsGreppableKeyValue() {
        val s = HookDebug.Snapshot().apply {
            configPresent = true
            secureFlag = true
            hideDevStatus = false
            hideAppList = true
            keyboxSpoof = true
            keyboxChainLength = 3
            keyboxLeafSerial = "f1c172a699eaf51d"
            revocationKnown = true
            keyboxRevoked = false
        }
        val dump = HookDebug.dump(s)
        // `adb logcat | grep farewell` has to show each fact on its own key=value line.
        assertTrue(dump.contains("[farewell] config.present=true"))
        assertTrue(dump.contains("[farewell] flag.secureFlag=true"))
        assertTrue(dump.contains("[farewell] flag.hideDevStatus=false"))
        assertTrue(dump.contains("[farewell] flag.hideAppList=true"))
        assertTrue(dump.contains("[farewell] keybox.spoof=true"))
        assertTrue(dump.contains("[farewell] keybox.chain=3"))
        assertTrue(dump.contains("[farewell] keybox.leafSerial=f1c172a699eaf51d"))
        assertTrue(dump.contains("[farewell] keybox.revocationKnown=true"))
        assertTrue(dump.contains("[farewell] keybox.revoked=false"))

        // One line per field, so a dump of N fields is N lines.
        assertEquals("one line per field", 9, dump.lines().size)
    }

    @Test
    fun dumpMarksAnUnloadedKeyboxAsUnknown() {
        // Nothing parsed yet: chain is -1 and the serial reads "unknown" rather than
        // silently looking like an empty-but-present value.
        val dump = HookDebug.dump(HookDebug.Snapshot())
        assertTrue(dump.contains("[farewell] keybox.chain=-1"))
        assertTrue(dump.contains("[farewell] keybox.leafSerial=unknown"))
        assertFalse("no config yet", dump.contains("config.present=true"))
    }

    @Test
    fun theNoConfigCaseIsVisibleNotSilent() {
        // parse() returns early for a null/empty blob, so without logMissing() the
        // single most common failure ("nothing has been pushed yet") produced no
        // output at all. The dump has to say config.present=false and chain=-1.
        val dump = HookDebug.dump(HookDebug.Snapshot())
        assertTrue(dump.contains("[farewell] config.present=false"))
        assertTrue(dump.contains("[farewell] keybox.chain=-1"))
        assertTrue(dump.contains("[farewell] keybox.leafSerial=unknown"))

        // Must not throw on the JVM: android.util.Log is a stub, and logMissing()
        // is called from the real config path.
        HookLog.setVerboseForTest(true)
        HookDebug.logMissing()
        HookDebug.logMissing() // second call is deduped, still no throw
    }

    @Test
    fun verboseIsOffUnlessSwitchedOn() {
        // android.util.Log is a stub here, so the platform answer is unavailable and
        // the default must stay quiet.
        assertFalse("verbose must default off", HookLog.verbose())
        HookLog.setVerboseForTest(true)
        assertTrue("test seam must be able to force verbose on", HookLog.verbose())
        HookLog.setVerboseForTest(false)
        assertFalse("and back off", HookLog.verbose())
    }
}
