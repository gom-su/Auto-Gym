package com.example.workoutpoc

import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.review
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Machine waits (requirement 8), rest excess (requirement 7) and the RIR rules (requirements 6 and 13). */
class MachineWaitTest {
    private val source = SessionSource("program", "Programa", "block", "week-1", "Semana", 1, "day", "Día")
    private fun set(n: Int, rest: Int = 75, rir: Int? = 2, setType: SetType = SetType.WORK) =
        PlannedSet("Remo", n, 2, 300, 8, rir, "p$n", "row", restSeconds = rest, setType = setType)
    private fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 1_000_000 + seconds * 1000, 1)

    private fun reducer(plan: List<PlannedSet>) = WorkoutReducer(plan)
    private fun WorkoutReducer.start(plan: List<PlannedSet>): WorkoutState {
        val loaded = load(WorkoutState(), plan, source, "s")
        return reduce(loaded, loaded.command(WorkoutEvent.StartWorkout), at(0), "s")
    }
    private fun WorkoutReducer.go(s: WorkoutState, event: WorkoutEvent, seconds: Long): WorkoutState =
        reduceRirReady(s, event, at(seconds))

    @Test fun `a wait armed during the rest starts exactly at the deadline and its span is excluded`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        assertEquals(Phase.Resting, s.phase)

        s = r.go(s, WorkoutEvent.StartMachineWait, 45)
        assertNotNull(s.openMachineWait?.pending)
        assertNull(s.runningWait)
        assertEquals(30_000L, s.restSignedBalance(at(45)))
        // Past the deadline the balance stays frozen at zero while the wait is pending-then-running.
        assertEquals(0L, s.restSignedBalance(at(80)))

        // The machine is released 18 s after the rest reached zero.
        s = r.go(s, WorkoutEvent.EndMachineWait, 93)
        assertEquals(18_000L, s.machineWaits.single().measuredMillis)
        assertEquals(18_000L, s.restSegments.single().pausedMillis)
        assertEquals(Phase.Resting, s.phase)

        // Confirming at 113 s: real rest is 95 s, 20 s over the 75 s objective (requirement 12 example).
        s = r.go(s, WorkoutEvent.ConfirmRest, 113)
        assertEquals(Phase.ActiveSet, s.phase)
        assertEquals(1, s.index)
        assertEquals(95_000L, s.restSegments.single().measuredMillis)
        val time = s.review().time!!
        assertEquals(95_000L, time.restMillis)
        assertEquals(18_000L, time.waitMillis)
        // Requirement 12 example: objective 75 s, real rest 95 s, excess 20 s, machine wait 18 s.
        val rest = s.review().rests.single()
        assertEquals(75, rest.objectiveSeconds)
        assertEquals(95_000L, rest.realMillis)
        assertEquals(20_000L, rest.excessMillis)
        assertEquals(18_000L, rest.machineWaitMillis)
        assertEquals(20_000L, s.review().totalExcessMillis)
    }

    @Test fun `a wait started after the objective is exceeded counts the excess before it and keeps it frozen`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)

        s = r.go(s, WorkoutEvent.StartMachineWait, 80)
        assertNotNull(s.runningWait)
        assertEquals(-5_000L, s.restSignedBalance(at(80)))

        s = r.go(s, WorkoutEvent.EndMachineWait, 100)
        assertEquals(20_000L, s.machineWaits.single().measuredMillis)
        assertEquals(-5_000L, s.restSignedBalance(at(100)))

        // Real rest is 100 - 20 = 80 s; the excess shows as -5 s before confirming.
        s = r.go(s, WorkoutEvent.ConfirmRest, 100)
        assertEquals(80_000L, s.restSegments.single().measuredMillis)
        assertEquals(20_000L, s.review().time!!.waitMillis)
    }

    @Test fun `a wait during an exercise suspends its activity time and resumes the same set`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))

        s = r.go(s, WorkoutEvent.StartMachineWait, 10)
        assertNotNull(s.runningWait)
        val blocked = s
        // Completing or advancing is refused while the machine wait runs (sent raw: no RIR is selected first).
        s = r.reduce(s, s.command(WorkoutEvent.CompleteSet), at(20), "s")
        assertEquals(blocked, s)

        s = r.go(s, WorkoutEvent.EndMachineWait, 40)
        assertEquals(Phase.ActiveSet, s.phase)
        assertEquals(0, s.index)
        assertEquals(30_000L, s.machineWaits.single().measuredMillis)

        s = r.go(s, WorkoutEvent.CompleteSet, 50)
        assertEquals(1, s.records.size)
        val time = s.review().time!!
        assertEquals(20_000L, time.activityMillis)
        assertEquals(30_000L, time.waitMillis)
    }

    @Test fun `a session pause freezes the rest balance`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.PauseSession, 30)
        assertEquals(45_000L, s.restSignedBalance(at(30)))
        assertEquals(45_000L, s.restSignedBalance(at(50)))
        s = r.go(s, WorkoutEvent.ResumeSession, 50)
        assertEquals(45_000L, s.restSignedBalance(at(50)))
        assertEquals(25_000L, s.restSignedBalance(at(70)))
    }

    @Test fun `a pause during a running wait is excluded from the wait and keeps the balance frozen`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.StartMachineWait, 80)
        s = r.go(s, WorkoutEvent.PauseSession, 90)
        assertEquals(-5_000L, s.restSignedBalance(at(95)))
        s = r.go(s, WorkoutEvent.ResumeSession, 100)
        s = r.go(s, WorkoutEvent.EndMachineWait, 110)
        // Wait: 30 s elapsed minus the 10 s pause = 20 s. Rest: 80 s real, 5 s over the 75 s objective.
        assertEquals(20_000L, s.machineWaits.single().measuredMillis)
        assertEquals(-5_000L, s.restSignedBalance(at(110)))
        s = r.go(s, WorkoutEvent.ConfirmRest, 110)
        assertEquals(80_000L, s.restSegments.single().measuredMillis)
    }

    @Test fun `a pause crossing the armed deadline starts no wait and moves the deadline on resume`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.StartMachineWait, 10)
        s = r.go(s, WorkoutEvent.PauseSession, 70)
        // The original deadline (75 s) passes during the pause: nothing starts.
        s = r.go(s, WorkoutEvent.ConfirmRest, 80)
        assertNull(s.openMachineWait!!.startedAtMillis)
        assertEquals(Phase.Resting, s.phase)
        s = r.go(s, WorkoutEvent.ResumeSession, 80)
        // Resumed 10 s later: the armed deadline moved to 85 s, so the wait is still pending.
        assertNull(s.openMachineWait!!.startedAtMillis)
        assertEquals(85_000L, s.openMachineWait!!.pending!!.elapsedMillis)
    }

    @Test fun `lowering the objective never starts an armed wait before the tap`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.StartMachineWait, 70)
        s = r.go(s, WorkoutEvent.ReduceRestTime, 71)
        // The new objective (60 s) had already passed when it was applied at 71 s: the wait starts at the adjustment,
        // never at 60 s, so no time before the change is attributed to the wait.
        s = r.go(s, WorkoutEvent.ConfirmRest, 80)
        assertEquals(at(71).wallMillis, s.openMachineWait!!.startedAtMillis)
        assertEquals(Phase.Resting, s.phase)
        assertEquals(-11_000L, s.restSignedBalance(at(80)))
    }

    @Test fun `a pending wait can be cancelled before the rest reaches zero`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.StartMachineWait, 10)
        s = r.go(s, WorkoutEvent.CancelPendingWait, 20)

        assertNull(s.openMachineWait)
        assertTrue(s.machineWaits.single().cancelled)
        s = r.go(s, WorkoutEvent.ConfirmRest, 75)
        assertEquals(75_000L, s.restSegments.single().measuredMillis)
        assertEquals(0L, s.review().time!!.waitMillis)
    }

    @Test fun `skipping the rest cancels a wait that was only armed`() {
        val r = reducer(listOf(set(1), set(2)))
        var s = r.start(listOf(set(1), set(2)))
        s = r.go(s, WorkoutEvent.CompleteSet, 0)
        s = r.go(s, WorkoutEvent.StartMachineWait, 10)
        s = r.go(s, WorkoutEvent.SkipRest, 30)

        assertEquals(Phase.ActiveSet, s.phase)
        assertEquals(1, s.index)
        assertNull(s.openMachineWait)
        assertTrue(s.machineWaits.single().cancelled)
    }

    @Test fun `a work set cannot complete without a RIR but RIR 0 is valid`() {
        val plan = listOf(set(1, rir = null), set(2))
        val r = reducer(plan)
        val started = r.start(plan)
        assertNull(started.values.rir)
        assertEquals(started, r.reduce(started, started.command(WorkoutEvent.CompleteSet), at(0), "s"))

        val rated = r.reduce(started, started.command(WorkoutEvent.SelectRir, 0), at(0), "s")
        val done = r.reduce(rated, rated.command(WorkoutEvent.CompleteSet), at(0), "s")
        assertEquals(Phase.Resting, done.phase)
        assertEquals(0, done.records.single().values.rir)
    }

    @Test fun `warm-up and approach sets have no RIR and never block completion`() {
        val plan = listOf(set(1, rir = null, setType = SetType.APPROACH), set(2))
        val r = reducer(plan)
        val started = r.start(plan)
        val afterSelect = r.reduce(started, started.command(WorkoutEvent.SelectRir, 3), at(0), "s")
        assertNull(afterSelect.values.rir)
        val done = r.reduce(afterSelect, afterSelect.command(WorkoutEvent.CompleteSet), at(0), "s")
        assertEquals(Phase.Resting, done.phase)
        assertNull(done.records.single().values.rir)
    }
}
