package io.github.hardbas.wearsync.app.ui

import io.github.hardbas.wearsync.app.WearSources

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source audit: the watch must not print a bedtime window or a skin-temperature
 * baseline the ring never reported.
 *
 * The phone publishes only measured values, but two screens filled the gaps on
 * their own: the sleep coach showed "22:45 - 23:30" whenever the window was
 * blank, and the temperature cards showed a 36.1 °C baseline whenever only the
 * offset had arrived. Both read as measurements. This pins their absence at the
 * source level, the same way the heart-rate and stress audits do.
 */
class NoSyntheticBedtimeAndBaselineTest {

    private val sleepDetail = read("src/main/java/io/github/hardbas/wearsync/app/ui/SleepDetailActivity.kt")
    private val temperatureDetail =
        read("src/main/java/io/github/hardbas/wearsync/app/ui/TemperatureDetailActivity.kt")

    @Test
    fun `a blank bedtime window is omitted instead of replaced by a fixed range`() {
        assertFalse(
            "the sleep coach still substitutes a literal for a blank bedtime window",
            Regex("""bedtimeWindow\.ifBlank\s*\{\s*"""").containsMatchIn(sleepDetail)
        )
        assertTrue(
            "the bedtime row must be drawn only for a window the phone reported",
            sleepDetail.contains("if (vitals.bedtimeWindow.isNotBlank())")
        )
    }

    @Test
    fun `no screen substitutes a baseline the ring did not report`() {
        val literalFallback = Regex("""skinTempBaseline\s+else\s+\d""")
        assertFalse(
            "the sleep temperature card still falls back to a literal baseline",
            literalFallback.containsMatchIn(sleepDetail)
        )
        assertFalse(
            "the temperature detail still falls back to a literal baseline",
            literalFallback.containsMatchIn(temperatureDetail)
        )
    }

    @Test
    fun `the temperature explanation quotes no baseline figure`() {
        val quotedTemperature = Regex("""\d+[.,]\d+\s*°C\)""")
        for (resources in listOf("src/main/res/values/strings.xml", "src/main/res/values-es/strings.xml")) {
            val explanation = read(resources).lineSequence()
                .firstOrNull { it.contains("name=\"temp_delta_explanation_desc\"") }
            assertTrue("$resources no longer declares temp_delta_explanation_desc", explanation != null)
            assertFalse(
                "$resources states a fixed baseline temperature as if it were the wearer's",
                quotedTemperature.containsMatchIn(explanation!!)
            )
        }
    }

    private fun read(relativePath: String): String {
        val file: File = WearSources.file(relativePath)
        assertTrue("cannot locate $file; the audit must run from the module", file.isFile)
        return file.readText()
    }
}
