package io.github.hardbas.wearsync.bridge.service

/**
 * The records this bridge may publish: the ones RingConn wrote itself.
 *
 * Health Connect is a shared store, and every type the reader queries can also hold rows from
 * another app. The reader used to take the ring's records and, when it found none, publish
 * whoever else had written the type, with nothing on the watch saying whose number it was.
 *
 * A metric the ring did not write is unknown rather than borrowed: the caller receives an empty
 * list and follows the zero or no-data path it already takes when Health Connect returns nothing.
 * This is the rule the heart-rate read has always applied; every other read now uses it too.
 */
internal object HealthConnectOrigin {

    /**
     * [records] that [isRingOrigin] accepts, in their original order.
     *
     * Empty is the honest answer for a type the ring did not write, so it is returned unchanged
     * and never replaced with another writer's rows.
     */
    fun <T> ringConnRecords(records: List<T>?, isRingOrigin: (T) -> Boolean): List<T> =
        records?.filter(isRingOrigin) ?: emptyList()
}
