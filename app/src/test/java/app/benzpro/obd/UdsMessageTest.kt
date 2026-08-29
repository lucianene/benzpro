package app.benzpro.obd

import app.benzpro.elm.ElmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsMessageTest {
    @Test
    fun nrc78SaeDecodesToC3F19WhichIsWhyWeMustNotParseIt() {
        assertEquals("C3F19", DtcDecoder.format(0x7F, 0x19))
        assertNull(UdsMessage.parseReadDtc(hex("7F 19 78 00")))
    }

    @Test
    fun nrc78IsPendingNotADtc() {
        val raw = hex("7F 19 78")
        assertTrue(UdsMessage.isPendingOnly(raw))
        assertNull(UdsMessage.parseReadDtc(raw))
        val nrc = UdsMessage.nrcAtStart(UdsMessage.assembleIsoTp(raw))!!
        assertEquals(0x19, nrc.sid)
        assertEquals(0x78, nrc.code)
        assertTrue(nrc.pending)
    }

    @Test
    fun isoTpSingleFrameNrc78() {
        val raw = hex("03 7F 19 78")
        assertEquals(hex("7F 19 78"), UdsMessage.assembleIsoTp(raw))
        assertTrue(UdsMessage.isPendingOnly(raw))
        assertNull(UdsMessage.parseReadDtc(raw))
    }

    @Test
    fun pendingThenPositiveIsParsed() {
        val raw = hex("7F 19 78 59 02 FF 11 27 12 2E")
        assertTrue(!UdsMessage.isPendingOnly(raw))
        val records = UdsMessage.parseReadDtc(raw)!!
        assertEquals(1, records.size)
        assertEquals(0x11, records[0].high)
        assertEquals(0x27, records[0].mid)
        assertEquals(0x12, records[0].ftb)
        assertEquals(0x2E, records[0].status)
        assertEquals("P1127", DtcDecoder.format(records[0].high, records[0].mid))
    }

    @Test
    fun positive59WithMaskAndThreeRecords() {
        val raw = hex("59 02 FF 11 27 12 2E B5 90 2F AE 40 41 D2 2E")
        val records = UdsMessage.parseReadDtc(raw)!!
        assertEquals(3, records.size)
        assertEquals("P1127", DtcDecoder.format(records[0].high, records[0].mid))
        assertEquals(0x12, records[0].ftb)
        assertEquals("B3590", DtcDecoder.format(records[1].high, records[1].mid))
        assertEquals(0x2F, records[1].ftb)
        assertEquals("C0041", DtcDecoder.format(records[2].high, records[2].mid))
        assertEquals(0xD2, records[2].ftb)
    }

    @Test
    fun neverParseWithout59() {
        assertNull(UdsMessage.parseReadDtc(hex("11 27 12 2E")))
        assertNull(UdsMessage.parseReadDtc(hex("7F 19 11")))
        assertNull(UdsMessage.parseReadDtc(hex("58 11 27 2E")))
    }

    @Test
    fun isoTpMultiFrame() {
        val raw = hex("10 0C 59 02 FF 11 27 12 21 2E B5 90 2F AE")
        val records = UdsMessage.parseReadDtc(raw)!!
        assertEquals(2, records.size)
        assertEquals("P1127", DtcDecoder.format(records[0].high, records[0].mid))
        assertEquals("B3590", DtcDecoder.format(records[1].high, records[1].mid))
    }

    @Test
    fun elmCanHeaderSkipped() {
        val bytes = ElmClient.hexBytes("7E8 03 7F 19 78")
        assertEquals(hex("03 7F 19 78"), bytes)
        assertTrue(UdsMessage.isPendingOnly(bytes))
    }

    @Test
    fun elmMultilineFrames() {
        val bytes = ElmClient.hexBytes("0: 59 02 FF 11 27 12\n1: 2E B5 90 2F AE")
        val records = UdsMessage.parseReadDtc(bytes)!!
        assertEquals(2, records.size)
    }

    private fun hex(s: String): List<Int> =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) }
}
