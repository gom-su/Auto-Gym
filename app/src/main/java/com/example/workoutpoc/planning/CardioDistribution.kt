package com.example.workoutpoc.planning

/**
 * Cardio that a plan requires on one of its days (contract §8). The rules belong to the plan that prescribes the
 * distribution; other programs pass their own rules or none.
 */
data class CardioDayRule(val dayId: String, val requiredSeconds: Int, val strengthAllowed: Boolean = true)

/** One message per problem a correction must fix; empty when the week satisfies [rules]. */
fun cardioDistributionViolations(week: WeekPlan, rules: List<CardioDayRule>): List<String> {
    val problems = mutableListOf<String>()
    for (rule in rules) {
        val day = week.days.firstOrNull { it.id == rule.dayId }
        if (day == null) {
            problems += "${week.name}: falta el día ${rule.dayId}."
            continue
        }
        val cardio = day.cardio
        when {
            cardio == null -> problems += "${day.name}: falta el cardio obligatorio de ${rule.requiredSeconds / 60} min."
            cardio.durationSeconds != rule.requiredSeconds ->
                problems += "${day.name}: el cardio debe durar ${rule.requiredSeconds / 60} min."
        }
        if (!rule.strengthAllowed && day.exercises.isNotEmpty()) problems += "${day.name}: este día debe ser solo cardio."
    }
    return problems
}
