package io.github.hardbas.wearsync.bridge.service

import kotlin.math.abs

/**
 * Decides whether a scraped token really is a skin-temperature reading.
 *
 * The widget tree is a flat bag of strings: numbers, labels, percentages and
 * battery levels all arrive together. A bare `4` is a plausible step count, a
 * plausible battery rank and a plausible temperature — and a wrong temperature
 * is the expensive one, because it is presented as a physiological deviation.
 *
 * Two rules keep it honest: a token has to *look* like a temperature (a sign, a
 * decimal part or a degree marker), and the value has to be physiologically
 * possible for a nocturnal deviation from the wearer's own baseline.
 */
object SkinTemperatureGuard {

    /**
     * A nocturnal skin-temperature deviation beyond this is not a measurement of
     * this sensor: the app's own thermoregulation bands place normal within
     * ±0.5 °C and "alert" at ±0.5 °C.
     */
    const val MAX_ABS_OFFSET_C = 1.5f

    /**
     * Plausible range for a skin-temperature baseline, in Celsius.
     *
     * The ceiling is 38.0, not 40.0: a nocturnal skin-temperature *baseline* of 40 °C is a
     * fever the sensor cannot measure and the app must never present. Measured phone evidence
     * showed no legitimate value above 38.0 — the only 40.0 the app ever held came from a stale
     * cache, and neither `SkinTemperatureRecord` nor `BodyTemperatureRecord` was readable from
     * Health Connect — so the upper bound is set at 38.0 °C, the practical limit of a
     * wearable skin probe, and anything above it is treated as unknown instead of admitted.
     */
    const val MIN_BASELINE_C = 30.0f
    const val MAX_BASELINE_C = 38.0f

    /** Sign, integer part, optional decimal part, optional degree marker, optional unit. */
    private val OFFSET_TOKEN =
        Regex("""^([+-]?)(\d{1,2})(?:[.,](\d{1,2}))?\s*(°)?\s*(C)?$""", RegexOption.IGNORE_CASE)

    /**
     * The temperature offset a token carries, or null when the token is not a
     * temperature at all.
     *
     * A bare integer is rejected on purpose: `4` must stay a step count instead
     * of becoming a four-degree fever.
     */
    fun offsetFrom(token: String): Float? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val match = OFFSET_TOKEN.matchEntire(trimmed) ?: return null

        val sign = match.groupValues[1]
        val integer = match.groupValues[2]
        val fraction = match.groupValues[3]
        val marked = match.groupValues[4].isNotEmpty() || match.groupValues[5].isNotEmpty()

        // A bare integer is never a temperature: `4` is the step count.
        if (sign.isEmpty() && fraction.isEmpty() && !marked) return null

        val magnitude = (integer + if (fraction.isEmpty()) "" else ".$fraction").toFloatOrNull() ?: return null
        val value = if (sign == "-") -magnitude else magnitude
        return value.takeIf { isPlausibleOffset(it) }
    }

    /** True when [value] is a possible deviation from a personal baseline. */
    fun isPlausibleOffset(value: Float): Boolean =
        value.isFinite() && abs(value) <= MAX_ABS_OFFSET_C

    /** True when [value] is a possible skin-temperature baseline. */
    fun isPlausibleBaseline(value: Float): Boolean =
        value.isFinite() && value >= MIN_BASELINE_C && value <= MAX_BASELINE_C

    /**
     * A stored offset passed through unchanged when plausible, and forgotten
     * otherwise. Guards both the incoming cache and the write to preferences.
     */
    fun sanitizeOffset(value: Float): Float = if (isPlausibleOffset(value)) value else 0f

    /** A stored baseline passed through when plausible, and forgotten otherwise. */
    fun sanitizeBaseline(value: Float): Float = if (isPlausibleBaseline(value)) value else 0f
}
