package brs.entity

import brs.objects.HistoricalMoments

open class FluxValue<T> constructor(val defaultValue: T, vararg val valueChanges: ValueChange<T>) {
    class ValueChange<T>(val historicalMoment: HistoricalMoments, val newValue: T)
}
