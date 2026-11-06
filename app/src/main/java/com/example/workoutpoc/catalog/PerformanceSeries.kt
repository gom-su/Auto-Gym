package com.example.workoutpoc.catalog

import com.example.workoutpoc.planning.WeekAnnotations

enum class ChartMetric(val label: String) { TOP_LOAD("Carga máxima"), TOTAL_REPS("Repeticiones"), VOLUME("Volumen"), MIN_RIR("RIR mínimo") }

/**
 * One chart point per exposure, in time order. [value] is null when the exposure has no completed work set (all
 * omitted, or only warm-ups) or no recorded RIR: the chart leaves a gap and never plots it as zero.
 */
data class ChartPoint(val startedAtMillis: Long, val value: Double?, val deload: Boolean, val label: String)

/** Values for one unit/basis group, so units are never mixed. Loads and volume are in the group's own unit. */
fun chartSeries(group: ExposureGroup, metric: ChartMetric, annotations: WeekAnnotations): List<ChartPoint> =
    group.exposures.sortedBy { it.startedAtMillis }.map { e ->
        val value: Double? = if (e.workSets == 0) null else when (metric) {
            ChartMetric.TOP_LOAD -> e.topLoadTenths?.div(10.0)
            ChartMetric.TOTAL_REPS -> e.totalReps.toDouble()
            ChartMetric.VOLUME -> e.volumeTenths / 10.0
            ChartMetric.MIN_RIR -> e.minRir?.toDouble()
        }
        ChartPoint(e.startedAtMillis, value, annotations.isDeload(e.programId, e.weekId), e.label)
    }
