package com.example.workoutpoc.data

import com.example.workoutpoc.domain.PlannedSet

object DemoWorkout {
    val sets: List<PlannedSet> = buildList {
        repeat(3) { add(PlannedSet("Press con mancuernas", it + 1, 3, 300, 8, 2)) }
        repeat(2) { add(PlannedSet("Jalón al pecho", it + 1, 2, 550, 10, 2)) }
        repeat(2) { add(PlannedSet("Elevaciones laterales", it + 1, 2, 125, 12, 3)) }
    }
}
