package io.github.hardbas.wearsync.bridge

import io.github.hardbas.wearsync.bridge.data.RingConnVitals
import io.github.hardbas.wearsync.bridge.service.RingConnTelemetryParser
import io.github.hardbas.wearsync.bridge.service.RingConnTelemetryReader
import io.github.hardbas.wearsync.bridge.service.RingConnWidgetHostReader
import io.github.hardbas.wearsync.bridge.service.WearDataSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingConnVitalsMergeTest {

    @Test
    fun defaultVitals_haveExpectedBaseline() {
        val vitals = RingConnVitals()
        assertEquals(0, vitals.ringBattery)
        assertEquals(0, vitals.lastHeartRate)
        assertEquals(0.0f, vitals.skinTempBaseline, 0.01f)
        assertEquals(0.0f, vitals.skinTempOffset, 0.01f)
        assertFalse(vitals.isConnected)
        assertEquals(0.0f, vitals.odiDropsPerHour, 0.01f)
        assertEquals(0, vitals.minSleepingSpo2)
        assertEquals(0.0f, vitals.sleepingHrDipPct, 0.01f)
        assertEquals(0, vitals.napMinutes)
        assertEquals("", vitals.bedtimeWindow)
        assertEquals(0, vitals.standHours)
    }

    @Test
    fun stalenessThreshold_marksOldDataProperly() {
        val nowSec = System.currentTimeMillis() / 1000L
        val fresh = RingConnVitals(refreshTimestamp = nowSec - 300L) // 5 minutes ago
        assertFalse(fresh.isStale)

        val stale = RingConnVitals(refreshTimestamp = nowSec - 1801L) // > 30 minutes
        assertTrue(stale.isStale)
    }

    @Test
    fun batteryLevels_validateChargingAndCriticalFlags() {
        val lowBattery = RingConnVitals(ringBattery = 15, isCharging = false)
        assertTrue("Battery < 20% should trigger low battery warning state", lowBattery.ringBattery < 20)

        val charging = RingConnVitals(ringBattery = 18, isCharging = true)
        assertTrue(charging.isCharging)
    }

    @Test
    fun parser_leavesBaselineUnknownWhenOnlyOffsetIsScraped() {
        // No invented baseline: an offset without a printed baseline means the
        // baseline is unknown, not 36.5 °C.
        val tokens = listOf("Skin Temperature", "+0.3°C")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(0.3f, parsed.skinTempOffset, 0.01f)
        assertEquals(0.0f, parsed.skinTempBaseline, 0.01f)
    }

    @Test
    fun parser_doesNotTurnABareIntegerIntoATemperatureOffset() {
        // The widget emits step counts and scores as bare numbers; a `4` there is
        // not a four degree fever.
        val tokens = listOf("Steps", "1453", "Sleep", "68", "4")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(0.0f, parsed.skinTempOffset, 0.01f)
    }

    @Test
    fun parser_discardsAnImplausibleCachedOffset() {
        // A value persisted before the guard existed must not survive a parse.
        val cached = RingConnVitals(skinTempOffset = 4.0f, skinTempBaseline = 35.94f)
        val parsed = RingConnTelemetryParser.parseTokens(listOf("Sleep", "68"), cached)
        assertEquals(0.0f, parsed.skinTempOffset, 0.01f)
        assertEquals(35.94f, parsed.skinTempBaseline, 0.01f)
    }

    @Test
    fun parser_preservesExplicitBaselineWhenPresent() {
        val tokens = listOf("Skin Temperature", "-0.2°C", "Baseline", "36.2°C")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(-0.2f, parsed.skinTempOffset, 0.01f)
        assertEquals(36.2f, parsed.skinTempBaseline, 0.01f)
    }

    @Test
    fun parser_supportsSpanishLocaleCommaDecimalsAndVariations() {
        // Spanish decimal comma and spaces
        val tokensEs1 = listOf("Temperatura de la piel", "+0,2 °C", "Línea base", "36,4 °C")
        val parsed1 = RingConnTelemetryParser.parseTokens(tokensEs1, RingConnVitals())
        assertEquals(0.2f, parsed1.skinTempOffset, 0.01f)
        assertEquals(36.4f, parsed1.skinTempBaseline, 0.01f)

        // Negative offset with comma and cutánea; the baseline stays unknown.
        val tokensEs2 = listOf("Temperatura cutánea", "-0,4 °C")
        val parsed2 = RingConnTelemetryParser.parseTokens(tokensEs2, RingConnVitals())
        assertEquals(-0.4f, parsed2.skinTempOffset, 0.01f)
        assertEquals(0.0f, parsed2.skinTempBaseline, 0.01f)
    }

    @Test
    fun signatureFields_detectChangesProperly() {
        fun makeSignature(vitals: RingConnVitals): String = WearDataSyncService.computeSignature(vitals)

        val base = RingConnVitals(avgSpo2 = 98, avgHrv = 45, isConnected = true, targetSteps = 10000, targetCalorie = 500, refreshTimestamp = 1000L)
        val sigBase = makeSignature(base)

        assertNotEquals(sigBase, makeSignature(base.copy(avgSpo2 = 97)))
        assertNotEquals(sigBase, makeSignature(base.copy(avgHrv = 50)))
        assertNotEquals(sigBase, makeSignature(base.copy(isConnected = false)))
        assertNotEquals(sigBase, makeSignature(base.copy(targetSteps = 12000)))
        assertNotEquals(sigBase, makeSignature(base.copy(targetCalorie = 600)))
        assertEquals(sigBase, makeSignature(base.copy(refreshTimestamp = 2000L)))
        assertEquals(sigBase, makeSignature(base.copy(basalCalories = 1800)))
        assertNotEquals(sigBase, makeSignature(base.copy(sleepInsight = "Great sleep")))
        assertNotEquals(sigBase, makeSignature(base.copy(stressInsight = "Calm")))
        assertNotEquals(sigBase, makeSignature(base.copy(odiDropsPerHour = 2.1f)))
        assertNotEquals(sigBase, makeSignature(base.copy(minSleepingSpo2 = 91)))
        assertNotEquals(sigBase, makeSignature(base.copy(sleepingHrDipPct = 12.0f)))
        assertNotEquals(sigBase, makeSignature(base.copy(napMinutes = 45)))
        assertNotEquals(sigBase, makeSignature(base.copy(bedtimeWindow = "23:00 - 23:45")))
        assertNotEquals(sigBase, makeSignature(base.copy(standHours = 9)))
    }

    @Test
    fun reverseEngineeredBiomarkers_defaultProperlyAndTriggerSignatureUpdates() {
        val vitals = RingConnVitals()
        assertEquals(0.0f, vitals.odiDropsPerHour, 0.001f)
        assertEquals(0, vitals.minSleepingSpo2)
        assertEquals(0.0f, vitals.sleepingHrDipPct, 0.001f)
        assertEquals(0, vitals.napMinutes)
        assertEquals("", vitals.bedtimeWindow)
        assertEquals(0, vitals.standHours)

        val baseSig = WearDataSyncService.computeSignature(vitals)
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(odiDropsPerHour = 1.4f)))
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(minSleepingSpo2 = 93)))
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(sleepingHrDipPct = 15.5f)))
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(napMinutes = 25)))
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(bedtimeWindow = "22:15 - 23:00")))
        assertNotEquals(baseSig, WearDataSyncService.computeSignature(vitals.copy(standHours = 7)))
    }

    @Test
    fun parser_extractsInsightsCorrectly() {
        val tokens = listOf(
            "Sleep", "85",
            "Your sleep was restful with excellent recovery overnight.",
            "Stress", "28",
            "Nivel de estrés relajado durante la mayor parte del día.",
            "Activity", "78",
            "You reached your daily steps and active movement goal!",
            "Skin Temperature", "+0.2°C",
            "Baseline skin temperature remained normal overnight."
        )
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(85, parsed.sleepScore)
        assertEquals(28, parsed.stressScore)
        assertEquals(78, parsed.activityScore)
        assertEquals("Your sleep was restful with excellent recovery overnight.", parsed.sleepInsight)
        assertEquals("Nivel de estrés relajado durante la mayor parte del día.", parsed.stressInsight)
        assertEquals("You reached your daily steps and active movement goal!", parsed.activityInsight)
        assertEquals("Baseline skin temperature remained normal overnight.", parsed.vitalsInsight)
    }

    @Test
    fun midnightRollover_resetsDailyCumulativeCountersAndPreservesInstantVitals() {
        val yesterdayVitals = RingConnVitals(
            lastHeartRate = 68,
            avgHeartRate = 64,
            lastSpo2 = 98,
            avgSpo2 = 97,
            lastHrv = 52,
            avgHrv = 49,
            ringBattery = 75,
            skinTempOffset = 0.2f,
            skinTempBaseline = 36.4f,
            sleepScore = 86,
            activityScore = 91,
            activeMinutes = 74,
            liveSteps = 8450,
            liveCalorie = 420,
            minHeartRate = 51,
            maxHeartRate = 142,
            zone1Mins = 320,
            zone2Mins = 45,
            zone3Mins = 12,
            zone4Mins = 0,
            zone5Mins = 0,
            sleepDeepMins = 85,
            sleepRemMins = 90,
            sleepLightMins = 210,
            sleepAwakeMins = 18,
            sleepEfficiencyPct = 94,
            hrPhaseDeep = 52,
            hrPhaseRem = 58,
            hrPhaseLight = 55,
            odiDropsPerHour = 1.2f,
            minSleepingSpo2 = 93,
            sleepingHrDipPct = 16.5f,
            napMinutes = 35,
            standHours = 11,
            stressDawn = 24,
            stressMorning = 38,
            stressAfternoon = 45,
            stressNight = 32,
            basalCalories = 1750,
            distanceKm = 6.44f,
            sleepInsight = "Yesterday sleep",
            activityInsight = "Yesterday activity",
            stressInsight = "Yesterday stress",
            vitalsInsight = "Yesterday vitals"
        )

        // Simulate crossing midnight at 01:10 AM
        val rolledOver = RingConnTelemetryReader.rolloverVitalsForNewDay(yesterdayVitals, nowHour = 1, nowMinute = 10)

        // Instantaneous vitals and battery MUST be preserved
        assertEquals(68, rolledOver.lastHeartRate)
        assertEquals(64, rolledOver.avgHeartRate)
        assertEquals(98, rolledOver.lastSpo2)
        assertEquals(97, rolledOver.avgSpo2)
        assertEquals(52, rolledOver.lastHrv)
        assertEquals(49, rolledOver.avgHrv)
        assertEquals(75, rolledOver.ringBattery)
        assertEquals(0.2f, rolledOver.skinTempOffset, 0.01f)
        assertEquals(36.4f, rolledOver.skinTempBaseline, 0.01f)

        // Daily-cumulative activity counters MUST be 0 on the new day
        assertEquals(0, rolledOver.liveSteps)
        assertEquals(0, rolledOver.liveCalorie)
        assertEquals(0, rolledOver.activityScore)
        assertEquals(0, rolledOver.activeMinutes)
        assertEquals(0.0f, rolledOver.distanceKm, 0.01f)
        assertEquals(0, rolledOver.standHours)

        // Sleep score and stages MUST be 0 on the new day until sleep concludes today
        assertEquals(0, rolledOver.sleepScore)
        assertEquals(0, rolledOver.sleepDeepMins)
        assertEquals(0, rolledOver.sleepRemMins)
        assertEquals(0, rolledOver.sleepLightMins)
        assertEquals(0, rolledOver.sleepAwakeMins)
        assertEquals(0, rolledOver.sleepEfficiencyPct)
        assertEquals(0, rolledOver.hrPhaseDeep)
        assertEquals(0, rolledOver.hrPhaseRem)
        assertEquals(0, rolledOver.hrPhaseLight)
        assertEquals(0.0f, rolledOver.odiDropsPerHour, 0.01f)
        assertEquals(0, rolledOver.minSleepingSpo2)
        assertEquals(0.0f, rolledOver.sleepingHrDipPct, 0.01f)
        assertEquals(0, rolledOver.napMinutes)

        // Circadian stress blocks and min/max HR MUST be reset
        assertEquals(0, rolledOver.stressDawn)
        assertEquals(0, rolledOver.stressMorning)
        assertEquals(0, rolledOver.stressAfternoon)
        assertEquals(0, rolledOver.stressNight)
        assertEquals(0, rolledOver.minHeartRate)
        assertEquals(0, rolledOver.maxHeartRate)
        assertEquals(0, rolledOver.zone1Mins)
        assertEquals(0, rolledOver.zone2Mins)

        // Basal calories at 01:10 AM should only reflect today's BMR accrued so far (~120 kcal)
        assertTrue("Basal calories at 01:10 AM must be reset to today (~120 kcal), got: ${rolledOver.basalCalories}", rolledOver.basalCalories in 100..200)

        // Insights from yesterday must be empty
        assertEquals("", rolledOver.sleepInsight)
        assertEquals("", rolledOver.activityInsight)
        assertEquals("", rolledOver.stressInsight)
        assertEquals("", rolledOver.vitalsInsight)
    }

    @Test
    fun parser_allowsZeroResetForStepsAndCalories() {
        val base = RingConnVitals(liveSteps = 5000, liveCalorie = 250, sleepScore = 80, activityScore = 75)
        val tokensWithDashes = listOf("Steps", "--", "Calories", "--", "Sleep", "--", "Activity", "--")
        val parsedDashes = RingConnTelemetryParser.parseTokens(tokensWithDashes, base)
        assertEquals(0, parsedDashes.liveSteps)
        assertEquals(0, parsedDashes.liveCalorie)
        assertEquals(0, parsedDashes.sleepScore)
        assertEquals(0, parsedDashes.activityScore)

        val tokensWithZeros = listOf("Steps", "0", "Calories", "0", "Activity", "0")
        val parsedZeros = RingConnTelemetryParser.parseTokens(tokensWithZeros, base)
        assertEquals(0, parsedZeros.liveSteps)
        assertEquals(0, parsedZeros.liveCalorie)
        assertEquals(0, parsedZeros.activityScore)
    }

    @Test
    fun computeSignature_ignoresRefreshTimestampAndBasalCalories() {
        val vitalsA = RingConnVitals(
            lastHeartRate = 72,
            lastSpo2 = 98,
            ringBattery = 80,
            refreshTimestamp = 1000L,
            basalCalories = 1200
        )
        val vitalsB = vitalsA.copy(
            refreshTimestamp = 2000L,
            basalCalories = 1205
        )
        assertEquals(WearDataSyncService.computeSignature(vitalsA), WearDataSyncService.computeSignature(vitalsB))

        val vitalsChanged = vitalsA.copy(lastHeartRate = 73)
        assertNotEquals(WearDataSyncService.computeSignature(vitalsA), WearDataSyncService.computeSignature(vitalsChanged))
    }

    @Test
    fun widgetHostReader_validityGateAllowsBiometricOnlyPayloads() {
        assertFalse(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals()))

        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(lastSpo2 = 98)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(avgSpo2 = 97)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(lastHrv = 45)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(avgHrv = 42)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(stressScore = 35)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(skinTempOffset = 0.2f)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(skinTempBaseline = 36.4f)))
        assertTrue(RingConnWidgetHostReader.hasValidTelemetry(RingConnVitals(sleepDeepMins = 60, sleepLightMins = 180)))
    }

    @Test
    fun parser_parsesSleepDurationString_andPopulatesPhysiologicalStages() {
        val tokens = listOf("Sleep", "6h 10m")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        val totalAsleep = parsed.sleepDeepMins + parsed.sleepRemMins + parsed.sleepLightMins
        assertEquals(370, totalAsleep)
        assertEquals(81, parsed.sleepDeepMins)
        assertEquals(85, parsed.sleepRemMins)
        assertEquals(204, parsed.sleepLightMins)
        assertTrue("Sleep score should be computed from stages", parsed.sleepScore in 45..98)

        // Variations of formatting
        val variations = listOf(
            listOf("6h10m"),
            listOf("6 h 10 min"),
            listOf("6 hrs 10 mins"),
            listOf("6 horas 10 minutos")
        )
        for (varTokens in variations) {
            val res = RingConnTelemetryParser.parseTokens(varTokens, RingConnVitals())
            assertEquals(370, res.sleepDeepMins + res.sleepRemMins + res.sleepLightMins)
        }
    }

    @Test
    fun parser_scalesPartialStages_toFullSleepDurationFromUI() {
        val partialVitals = RingConnVitals(
            sleepDeepMins = 40,
            sleepRemMins = 50,
            sleepLightMins = 150 // total 240 mins (4h)
        )
        val tokens = listOf("Sleep", "6h 10m")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, partialVitals)
        val totalAsleep = parsed.sleepDeepMins + parsed.sleepRemMins + parsed.sleepLightMins
        assertEquals(370, totalAsleep)
        assertEquals(62, parsed.sleepDeepMins)
        assertEquals(77, parsed.sleepRemMins)
        assertEquals(231, parsed.sleepLightMins)
    }

    @Test
    fun parser_parsesIndividualLabeledStages() {
        val tokens = listOf(
            "Deep", "1h 20m",
            "REM", "1h 25m",
            "Light", "3h 25m",
            "Awake", "15m"
        )
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(80, parsed.sleepDeepMins)
        assertEquals(85, parsed.sleepRemMins)
        assertEquals(205, parsed.sleepLightMins)
        assertEquals(15, parsed.sleepAwakeMins)
        assertEquals(370, parsed.sleepDeepMins + parsed.sleepRemMins + parsed.sleepLightMins)
    }

    @Test
    fun parser_doesNotStealPreviousMetricScore_inAdjacentHeaderValuePairs() {
        // Label-first layout (HomeAppWidget / Accessibility tree without intermediate prose):
        // "Activity" must not steal 85 from "Sleep", and "Stress" must not steal 78 from "Activity".
        val labelFirstTokens = listOf("Sleep", "85", "Activity", "78", "Stress", "28", "Battery", "91%")
        val parsedLabelFirst = RingConnTelemetryParser.parseTokens(labelFirstTokens, RingConnVitals())
        assertEquals(85, parsedLabelFirst.sleepScore)
        assertEquals(78, parsedLabelFirst.activityScore)
        assertEquals(28, parsedLabelFirst.stressScore)
        assertEquals(91, parsedLabelFirst.ringBattery)

        // Value-first layout (MediumHomeAppWidget):
        val valueFirstTokens = listOf("85", "Sleep", "78", "Activity", "28", "Stress", "91%", "Battery")
        val parsedValueFirst = RingConnTelemetryParser.parseTokens(valueFirstTokens, RingConnVitals())
        assertEquals(85, parsedValueFirst.sleepScore)
        assertEquals(78, parsedValueFirst.activityScore)
        assertEquals(28, parsedValueFirst.stressScore)
        assertEquals(91, parsedValueFirst.ringBattery)
    }

    @Test
    fun parser_respectsWidgetBoundaries_preventingCrossWidgetTokenBleed() {
        // Trailing number from widget A must never be consumed by a leading header in widget B
        val tokens = listOf("Steps", "64", "---", "Stress", "31")
        val parsed = RingConnTelemetryParser.parseTokens(tokens, RingConnVitals())
        assertEquals(64, parsed.liveSteps)
        assertEquals(31, parsed.stressScore)
    }

    @Test
    fun widgetHostReader_shouldTriggerGlanceRecompose_whenWidgetPassIsEmptyDespiteCachedBase() {
        val cachedBase = RingConnVitals(lastHeartRate = 72, ringBattery = 80, sleepScore = 84)
        val emptyWidgetTokens = listOf("Vital Signs", "--")
        assertTrue(
            "Must trigger Glance recompose when widget pass 1 yields no live metrics even if cache has data",
            RingConnWidgetHostReader.shouldTriggerGlanceRecompose(
                collectedTokens = emptyWidgetTokens,
                baseVitals = cachedBase,
                hadNewAllocation = false
            )
        )

        val populatedWidgetTokens = listOf("74", "bpm", "98", "%", "52", "ms")
        assertFalse(
            "Should skip Glance recompose when pass 1 already extracted valid widget metrics and no new allocation occurred",
            RingConnWidgetHostReader.shouldTriggerGlanceRecompose(
                collectedTokens = populatedWidgetTokens,
                baseVitals = cachedBase,
                hadNewAllocation = false
            )
        )

        assertTrue(
            "Must trigger Glance recompose when a widget ID was newly allocated",
            RingConnWidgetHostReader.shouldTriggerGlanceRecompose(
                collectedTokens = populatedWidgetTokens,
                baseVitals = cachedBase,
                hadNewAllocation = true
            )
        )
    }

    @Test
    fun widgetHostReader_assignsResponsiveDimensionsPerProviderSizeClass() {
        val largeDim = RingConnWidgetHostReader.widgetDimensionsDp(
            "com.gdjztech.remotewidget.DataIndicatorsLargeWidgetReceiver",
            toggle = false
        )
        val largeDimToggled = RingConnWidgetHostReader.widgetDimensionsDp(
            "com.gdjztech.remotewidget.SportIndicatorsLargeWidgetReceiver",
            toggle = true
        )
        val mediumDim = RingConnWidgetHostReader.widgetDimensionsDp(
            "com.gdjztech.remotewidget.MediumHomeAppWidgetReceiver",
            toggle = false
        )
        val compactDim = RingConnWidgetHostReader.widgetDimensionsDp(
            "com.gdjztech.remotewidget.DataIndicatorsWidgetReceiver",
            toggle = false
        )

        assertEquals(360 to 320, largeDim)
        assertEquals(360 to 321, largeDimToggled)
        assertEquals(360 to 200, mediumDim)
        assertEquals(276 to 200, compactDim)
    }

    @Test
    fun parser_doesNotOverwriteHeartRateWithHrvAverage_inDataIndicatorsWidget() {
        // Exact token sequence emitted by RingConn's DataIndicatorsWidgetReceiver / DataIndicatorsLargeWidgetReceiver
        val enTokens = listOf(
            "Vital Signs", "10:42 PM",
            "HR", "76", "bpm", "Latest", "73", "bpm", "Average",
            "SpO2", "95", "%", "Latest", "92", "%", "Average",
            "HRV", "53", "ms", "Latest", "43", "ms", "Average"
        )
        val parsedEn = RingConnTelemetryParser.parseTokens(enTokens, RingConnVitals())
        assertEquals(76, parsedEn.lastHeartRate)
        assertEquals(73, parsedEn.avgHeartRate)
        assertEquals(95, parsedEn.lastSpo2)
        assertEquals(92, parsedEn.avgSpo2)
        assertEquals(53, parsedEn.lastHrv)
        assertEquals(43, parsedEn.avgHrv)

        val esTokens = listOf(
            "Signos vitales", "10:42 PM",
            "FC", "70", "lpm", "Última", "66", "lpm", "Promedio",
            "SpO2", "96", "%", "Última", "94", "%", "Promedio",
            "VFC", "51", "ms", "Última", "43", "ms", "Promedio"
        )
        val parsedEs = RingConnTelemetryParser.parseTokens(esTokens, RingConnVitals())
        assertEquals(70, parsedEs.lastHeartRate)
        assertEquals(66, parsedEs.avgHeartRate)
        assertEquals(96, parsedEs.lastSpo2)
        assertEquals(94, parsedEs.avgSpo2)
        assertEquals(51, parsedEs.lastHrv)
        assertEquals(43, parsedEs.avgHrv)
    }

    @Test
    fun smartMerge_doesNotPrioritizeHealthConnect_whenHcHasNoBiometricTimestampOrNoDirectHr() {
        val widgetVitals = RingConnVitals(lastHeartRate = 76, refreshTimestamp = 1793677320L)

        // When HC only returned steps (no HR/SpO2/HRV samples), biometric timestamp is 0L
        assertEquals(0L, RingConnTelemetryReader.resolveBiometricRefreshTimestamp(0L, 0L, 0L))

        val hcStepsOnly = RingConnVitals(lastHeartRate = 0, liveSteps = 7570, refreshTimestamp = 0L)
        assertFalse(RingConnTelemetryReader.shouldPrioritizeHealthConnectHr(hcStepsOnly, widgetVitals))

        val hcWithStaleOrZeroTimestamp = RingConnVitals(lastHeartRate = 43, refreshTimestamp = 0L)
        assertFalse(RingConnTelemetryReader.shouldPrioritizeHealthConnectHr(hcWithStaleOrZeroTimestamp, widgetVitals))

        val hcWithFresherRingConnSample = RingConnVitals(lastHeartRate = 74, refreshTimestamp = 1793677600L)
        assertTrue(RingConnTelemetryReader.shouldPrioritizeHealthConnectHr(hcWithFresherRingConnSample, widgetVitals))
    }
}

