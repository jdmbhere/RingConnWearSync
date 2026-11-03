package io.github.hardbas.wearsync.app.data

/** One sleep stage and the mean heart rate the ring measured inside it. */
data class PhaseHeartRate(
    val phase: SleepStage,
    val bpm: Int
)

/** The three stages RingConn reports a mean heart rate for. */
enum class SleepStage {
    DEEP,
    LIGHT,
    REM
}

/**
 * The stage heart rates the ring actually measured, deep to rem.
 *
 * Zero means the night had no heart-rate samples inside that stage. That is an
 * unknown that resolves itself as soon as the first measured night arrives, so the
 * row is left out entirely instead of being drawn as a `--` placeholder the wearer
 * would read as "the phone has not synced". An empty list means no stage was
 * measured at all and the card shows a short honest line instead.
 */
object SleepPhaseHeartRates {

    fun measured(vitals: RingConnVitals): List<PhaseHeartRate> {
        val readings = listOf(
            PhaseHeartRate(SleepStage.DEEP, vitals.hrPhaseDeep),
            PhaseHeartRate(SleepStage.LIGHT, vitals.hrPhaseLight),
            PhaseHeartRate(SleepStage.REM, vitals.hrPhaseRem)
        )
        return readings.filter { it.bpm > 0 }
    }
}
