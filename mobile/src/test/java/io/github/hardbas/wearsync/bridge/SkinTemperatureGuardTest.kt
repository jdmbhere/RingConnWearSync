package io.github.hardbas.wearsync.bridge

import io.github.hardbas.wearsync.bridge.service.SkinTemperatureGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A temperature offset is the one scraped value that gets presented as a
 * physiological deviation, so a bare `4` from the widget must never become
 * "+4.0 °C". These cases are the tokens the RingConn widget actually emits.
 */
class SkinTemperatureGuardTest {

    @Test
    fun `a bare integer is a step count, not a temperature`() {
        assertNull(SkinTemperatureGuard.offsetFrom("4"))
        assertNull(SkinTemperatureGuard.offsetFrom("2"))
        assertNull(SkinTemperatureGuard.offsetFrom("0"))
    }

    @Test
    fun `a signed offset is accepted`() {
        assertEquals(0.4f, SkinTemperatureGuard.offsetFrom("+0.4")!!, 0.001f)
        assertEquals(-0.3f, SkinTemperatureGuard.offsetFrom("-0.3")!!, 0.001f)
    }

    @Test
    fun `degree and unit markers are accepted`() {
        assertEquals(-0.3f, SkinTemperatureGuard.offsetFrom("-0,3 °C")!!, 0.001f)
        assertEquals(0.2f, SkinTemperatureGuard.offsetFrom("0.2°C")!!, 0.001f)
        assertEquals(0.2f, SkinTemperatureGuard.offsetFrom("+0.2 c")!!, 0.001f)
    }

    @Test
    fun `a zero offset is still a reading when it is written as a decimal`() {
        assertEquals(0.0f, SkinTemperatureGuard.offsetFrom("0.0")!!, 0.001f)
        assertEquals(0.0f, SkinTemperatureGuard.offsetFrom("±0.0") ?: 0.0f, 0.001f)
    }

    @Test
    fun `an implausible deviation is rejected even when well formed`() {
        assertNull(SkinTemperatureGuard.offsetFrom("4,5"))
        assertNull(SkinTemperatureGuard.offsetFrom("+2.0"))
        assertNull(SkinTemperatureGuard.offsetFrom("-1.6"))
    }

    @Test
    fun `a baseline value is not mistaken for an offset`() {
        assertNull(SkinTemperatureGuard.offsetFrom("35.94"))
        assertNull(SkinTemperatureGuard.offsetFrom("36.1 °C"))
    }

    @Test
    fun `labels and percentages are not temperatures`() {
        assertNull(SkinTemperatureGuard.offsetFrom("Sleep"))
        assertNull(SkinTemperatureGuard.offsetFrom("85%"))
        assertNull(SkinTemperatureGuard.offsetFrom(""))
    }

    @Test
    fun `offset plausibility follows the guard band`() {
        assertTrue(SkinTemperatureGuard.isPlausibleOffset(0f))
        assertTrue(SkinTemperatureGuard.isPlausibleOffset(1.5f))
        assertTrue(SkinTemperatureGuard.isPlausibleOffset(-1.5f))
        assertFalse(SkinTemperatureGuard.isPlausibleOffset(1.6f))
        assertFalse(SkinTemperatureGuard.isPlausibleOffset(-1.6f))
        assertFalse(SkinTemperatureGuard.isPlausibleOffset(Float.NaN))
    }

    @Test
    fun `baseline plausibility follows the skin temperature range`() {
        assertTrue(SkinTemperatureGuard.isPlausibleBaseline(35.94f))
        assertTrue(SkinTemperatureGuard.isPlausibleBaseline(33.5f))
        assertTrue(SkinTemperatureGuard.isPlausibleBaseline(30.0f))
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(29.9f))
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(40.1f))
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(0f))
    }

    @Test
    fun `a baseline plausibility ceiling rejects a forty degree reading`() {
        // A skin-temperature baseline of 40.0 °C is physiologically absurd and is
        // exactly what a stale cache kept resurrecting. Real nocturnal baselines sit
        // around 33-36 °C.
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(40.0f))
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(38.1f))
        assertTrue(SkinTemperatureGuard.isPlausibleBaseline(36.2f))
    }

    @Test
    fun `a stored forty degree baseline is forgotten`() {
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeBaseline(40.0f), 0.001f)
        assertEquals(36.2f, SkinTemperatureGuard.sanitizeBaseline(36.2f), 0.001f)
    }

    @Test
    fun `a stored implausible offset is forgotten`() {
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeOffset(4.0f), 0.001f)
        assertEquals(-0.4f, SkinTemperatureGuard.sanitizeOffset(-0.4f), 0.001f)
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeOffset(0.0f), 0.001f)
    }

    @Test
    fun `a stored implausible baseline is forgotten`() {
        assertEquals(35.94f, SkinTemperatureGuard.sanitizeBaseline(35.94f), 0.001f)
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeBaseline(41.0f), 0.001f)
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeBaseline(0.0f), 0.001f)
    }

    @Test
    fun `a measured baseline above the sensor range is rejected and a real one is accepted`() {
        // The guard also stands between a Health Connect record and the watch: a provider that
        // writes 40.0 °C must not reach the tile, while the measured 35.91 °C baseline must.
        assertFalse(SkinTemperatureGuard.isPlausibleBaseline(40.0f))
        assertEquals(0.0f, SkinTemperatureGuard.sanitizeBaseline(40.0f), 0.001f)
        assertTrue(SkinTemperatureGuard.isPlausibleBaseline(35.91f))
        assertEquals(35.91f, SkinTemperatureGuard.sanitizeBaseline(35.91f), 0.001f)
    }
}
