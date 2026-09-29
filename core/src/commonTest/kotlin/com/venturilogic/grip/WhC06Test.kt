package com.venturilogic.grip

import com.venturilogic.grip.devices.WhC06
import com.venturilogic.grip.devices.WhC06.PayloadSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhC06Test {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Advertisement captured in sebws/Crane's WHC06Mock (Apple form, company id first),
    // with weight bytes 0x04 0xD2 = 1234 -> 12.34 kg.
    private val apple = bytes(
        0x00, 0x01, // company id 0x0100, little-endian
        0x02, 0x03, 0x11, 0x2a, 0xc0, 0x19, 0x11, 0x23, 0xe1, 0x01,
        0x04, 0xD2, // weight
        0x01, 0xf4, 0x01, 0xd4, 0xcb,
    )
    private val android = apple.copyOfRange(2, apple.size)

    @Test
    fun appleAndAndroidFormsDecodeIdentically() {
        val a = WhC06.parse(apple, PayloadSource.WITH_COMPANY_ID)!!
        val b = WhC06.parse(android, PayloadSource.WITHOUT_COMPANY_ID)!!
        assertEquals(12.34, a.weight, 1e-9)
        assertEquals(a, b)
    }

    @Test
    fun statusNibbles() {
        val r = WhC06.parse(android, PayloadSource.WITHOUT_COMPANY_ID)!!
        assertEquals(0, r.stableFlag)
        assertEquals(1, r.unitFlag)
    }

    @Test
    fun negativeWeightIsSigned() {
        val p = android.copyOf().also { it[10] = 0xFF.toByte(); it[11] = 0x38.toByte() } // -200
        assertEquals(-2.0, WhC06.parse(p, PayloadSource.WITHOUT_COMPANY_ID)!!.weight, 1e-9)
    }

    @Test
    fun rejectsWrongCompanyIdOnApple() {
        val p = apple.copyOf().also { it[0] = 0x4C } // Apple's 0x004C
        assertNull(WhC06.parse(p, PayloadSource.WITH_COMPANY_ID))
    }

    @Test
    fun rejectsShortPayloadAndToleratesMissingStatus() {
        assertNull(WhC06.parse(android.copyOf(11), PayloadSource.WITHOUT_COMPANY_ID))
        val noStatus = WhC06.parse(android.copyOf(12), PayloadSource.WITHOUT_COMPANY_ID)!!
        assertEquals(12.34, noStatus.weight, 1e-9)
        assertNull(noStatus.stableFlag)
    }

    @Test
    fun staleAfterTenSeconds() {
        assertTrue(!WhC06.isStale(0, 10_000))
        assertTrue(WhC06.isStale(0, 10_001))
    }
}
