package ai.xybrid.mediator

import org.junit.Assert.*
import org.junit.Test

class ConversationTest {
    private fun dialogue(): Conversation = Conversation().start("Alex", "Sam")
        .commit(Speaker.A, "I need an update so I can plan delivery.")
        .commit(Speaker.B, "I need quiet time to finish the work.")

    private fun analysis() = Analysis(
        listOf(SpeakerView(Speaker.A, Claim("Updates", listOf(1)), null),
            SpeakerView(Speaker.B, Claim("Quiet time", listOf(2)), null)),
        Claim("Finish the work", listOf(1, 2)),
        Intervention(Claim("Would a daily update help?", listOf(1, 2)),
            Claim("Try a daily update", listOf(1, 2)))
    )

    @Test fun oneSpeakerCannotTriggerIntervention() {
        val session = Conversation().start("Alex", "Sam").commit(Speaker.A, "Updates please")
        val updated = session.apply(session.revision, analysis(), 100_000)
        assertNull(updated.pending)
        assertFalse(updated.chimeDue(100_000))
    }

    @Test fun agreementRequiresOpeningAndTwoDistinctConfirmations() {
        val session = dialogue()
        val pending = session.apply(session.revision, analysis(), 100_000)
        assertNull(pending.confirm(Speaker.A).agreement)
        val one = pending.openSuggestion().confirm(Speaker.A).confirm(Speaker.A)
        assertFalse(one.agreement!!.complete)
        assertTrue(one.confirm(Speaker.B).agreement!!.complete)
    }

    @Test fun editingOrResettingRejectsLateAnalysis() {
        val original = dialogue()
        val edited = original.edit(1, "I need a weekly update")
        assertEquals(edited, edited.apply(original.revision, analysis(), 0))
        val reset = original.end()
        assertEquals(reset, reset.apply(original.revision, analysis(), 0))
        assertTrue(reset.turns.isEmpty())
    }

    @Test fun recordingClearsSuggestionAndSuppressesChime() {
        val session = dialogue()
        val ready = session.apply(session.revision, analysis(), 100_000)
        assertTrue(ready.chimeDue(100_000))
        val recording = ready.beginTurn(Speaker.A)
        assertNull(recording.pending)
        assertFalse(recording.chimeDue(100_000))
    }

    @Test fun cooldownAndAlreadyChimedAreRespected() {
        val session = dialogue()
        val chimed = session.apply(session.revision, analysis(), 100_000).markChimed(100_000)
        assertFalse(chimed.chimeDue(200_000))
        val next = chimed.commit(Speaker.A, "Could we try tomorrow?")
        val ready = next.apply(next.revision, analysis(), 110_000)
        assertFalse(ready.chimeDue(110_000))
        assertTrue(ready.chimeDue(160_000))
    }

    @Test fun correctionRetainsPreviousMapSourcesAndInvalidatesConfirmations() {
        val session = dialogue()
        val accepted = session.apply(session.revision, analysis(), 0)
            .openSuggestion().confirm(Speaker.A).confirm(Speaker.B)
        val edited = accepted.edit(1, "Actually I want fewer meetings")
        assertEquals(accepted.analysis, edited.analysis)
        assertEquals(accepted.turns, edited.analysisTurns)
        assertNotEquals(edited.turns, edited.analysisTurns)
        assertNull(edited.pending)
        assertNull(edited.agreement)
    }

    @Test fun emptyTurnsAreSkippedAndHistoryIsCapped() {
        var session = Conversation().start("Alex", "Sam")
        assertEquals(session, session.commit(Speaker.A, "  "))
        repeat(8) { session = session.commit(Speaker.A, "Turn $it") }
        assertThrows(IllegalArgumentException::class.java) { session.commit(Speaker.B, "Ninth") }
    }

    @Test fun changedProposalResetsBothConfirmations() {
        val session = dialogue()
        val accepted = session.apply(session.revision, analysis(), 0).openSuggestion()
            .confirm(Speaker.A).confirm(Speaker.B)
        val next = analysis().copy(intervention = Intervention(Claim("Try a weekly update?", listOf(1, 2)),
            Claim("Try a weekly update", listOf(1, 2))))
        assertNull(accepted.apply(accepted.revision, next, 1000).agreement)
    }

    @Test fun completedAgreementSurvivesDismissingOrBeginningAnotherTurn() {
        val session = dialogue()
        val accepted = session.apply(session.revision, analysis(), 0).openSuggestion()
            .confirm(Speaker.A).confirm(Speaker.B)
        assertTrue(accepted.dismissSuggestion().agreement!!.complete)
        assertTrue(accepted.beginTurn(Speaker.A).agreement!!.complete)
        assertTrue(accepted.commit(Speaker.A, "Thanks, that helps.").agreement!!.complete)
    }

    @Test fun completedAgreementSurvivesAnalysisWithNoReplacementProposal() {
        val session = dialogue()
        val accepted = session.apply(session.revision, analysis(), 0).openSuggestion()
            .confirm(Speaker.A).confirm(Speaker.B).commit(Speaker.A, "Thanks, that helps.")
        val noSuggestion = analysis().copy(intervention = null)
        assertTrue(accepted.apply(accepted.revision, noSuggestion, 1000).agreement!!.complete)
        val justQuestion = analysis().copy(intervention = Intervention(Claim("How did it go?", listOf(1, 2)), null))
        assertTrue(accepted.apply(accepted.revision, justQuestion, 1000).agreement!!.complete)
    }

    @Test fun endedSessionCannotBeRestartedByNativeCallback() {
        val original = dialogue()
        val ended = original.end()
        assertFalse(ended.apply(original.revision, analysis(), 0).active)
        assertNull(ended.apply(original.revision, analysis(), 0).pending)
    }

    @Test fun alreadyOpenedQuestionDoesNotRingLater() {
        val session = dialogue()
        val opened = session.apply(session.revision, analysis(), 0).openSuggestion()
        assertFalse(opened.chimeDue(100_000))
    }
}
