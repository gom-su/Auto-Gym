package com.example.workoutpoc

import com.example.workoutpoc.catalog.CatalogCodec
import com.example.workoutpoc.catalog.CatalogExercise
import com.example.workoutpoc.planning.LoadUnit
import org.junit.Assert.*
import org.junit.Test

/** Contract §2: the preferred unit of an exercise is kg or lb only; absent means no preference yet. */
class CatalogPreferredUnitTest {
    private fun exercise(unit: LoadUnit?) = CatalogExercise(id = "press", name = "Press", preferredUnit = unit)

    @Test fun `kg, lb and no preference are accepted`() {
        CatalogCodec.validate(exercise(LoadUnit.KG))
        CatalogCodec.validate(exercise(LoadUnit.LB))
        CatalogCodec.validate(exercise(null))
    }

    @Test fun `machine settings and bodyweight are not preferred units`() {
        for (unit in listOf(LoadUnit.MACHINE_SETTING, LoadUnit.BODYWEIGHT)) {
            val error = runCatching { CatalogCodec.validate(exercise(unit)) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("kg o lb"))
        }
    }
}
