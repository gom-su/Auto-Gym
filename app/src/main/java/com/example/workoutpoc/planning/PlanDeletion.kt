package com.example.workoutpoc.planning

import com.example.workoutpoc.data.SessionCommentRepository
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.exchange.ExchangeRepository
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.proposal.ProposalRepository

/** What deleting a plan removes: its versions, its program's weeks and everything recorded against that program. */
data class PlanDeletionCounts(
    val outlineVersions: Int, val weeks: Int, val sessions: Int, val weekAnnotations: Int,
    /** Proposal decisions plus the expectations tied to them. */
    val proposalRecords: Int, val comments: Int, val exchangePackages: Int
) {
    val total get() = outlineVersions + weeks + sessions + weekAnnotations + proposalRecords + comments + exchangePackages
    companion object { val NONE = PlanDeletionCounts(0, 0, 0, 0, 0, 0, 0) }
}

/**
 * Deletes one plan and every trace of its program: the plan versions, the program and its weeks, the sessions recorded
 * against it, its week annotations, proposal decisions and expectations, session comments and imported exchange packages.
 * Other programs, body measurements, the exercise catalog, goals and rules are never touched.
 *
 * Refused while a session of that program is in progress, before anything is removed. Each store is cleared in its own
 * step; every step is idempotent, so a failure midway is finished by running the deletion again.
 */
class PlanDeletion(
    private val outlines: PlanOutlineRepository, private val programs: ProgramRepository,
    private val weekAnnotations: WeekAnnotationRepository, private val proposals: ProposalRepository,
    private val comments: SessionCommentRepository, private val exchanges: ExchangeRepository,
    private val workout: WorkoutRepository
) {
    /** What [delete] would remove now, without writing anything. */
    fun preview(programId: String): PlanDeletionCounts {
        val store = outlines.outlines.value
        val program = programs.library.value.programs.find { it.id == programId }
        val archived = workout.archived.value.sessions
        val current = workout.state.value
        val decisions = proposals.state.value.decisions.filter { it.programId == programId }
        val importIds = decisions.map { it.importId }.toSet()
        val annotations = weekAnnotations.annotations.value
        return PlanDeletionCounts(
            outlineVersions = (store.active + store.replaced + store.inactive).count { it.programId == programId },
            weeks = program?.blocks?.sumOf { it.weeks.size } ?: 0,
            sessions = archived.count { it.source?.programId == programId } + if (current.source?.programId == programId) 1 else 0,
            weekAnnotations = annotations.deloads.count { it.programId == programId } + annotations.notPerformed.count { it.programId == programId },
            proposalRecords = decisions.size + proposals.state.value.expectations.count { it.importId in importIds },
            comments = comments.log.value.comments.count { it.programId == programId },
            exchangePackages = exchanges.archive.value.packages.count {
                (it.type == ExchangeType.PlanOutline && it.outline?.programId == programId) ||
                    (it.type == ExchangeType.WeekProposal && it.proposal?.programId == programId)
            }
        )
    }

    /** Removes the plan and its program's records. Returns what was actually removed. */
    suspend fun delete(programId: String): PlanDeletionCounts {
        // Weeks are counted before the program is removed: programs.removeProgram returns programs, not weeks.
        val before = preview(programId)
        // The sessions go first: they refuse while one of this program is in progress, before anything else is touched.
        val (sessions, _) = workout.deleteProgramRecords(programId)
        return try {
            val outlineCount = outlines.removeProgram(programId)
            programs.removeProgram(programId)
            val weekCount = before.weeks
            val annotations = weekAnnotations.removeProgram(programId)
            val decisions = proposals.removeProgram(programId)
            val commentCount = comments.removeProgram(programId)
            val packages = exchanges.removeProgram(programId)
            PlanDeletionCounts(outlineVersions = outlineCount, weeks = weekCount, sessions = sessions,
                weekAnnotations = annotations, proposalRecords = decisions, comments = commentCount, exchangePackages = packages)
        } catch (e: Exception) {
            throw IllegalStateException("El borrado se interrumpió y quedó a medias. Vuelve a intentarlo: lo ya borrado no se restaura y el reintento termina el resto.", e)
        }
    }
}
