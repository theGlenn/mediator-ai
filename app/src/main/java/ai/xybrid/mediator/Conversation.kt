package ai.xybrid.mediator

internal enum class Speaker { A, B }
internal data class Turn(val id: Int, val speaker: Speaker, val text: String, val typed: Boolean = false)
internal data class Claim(val text: String, val evidence: List<Int>)
internal data class SpeakerView(val speaker: Speaker, val request: Claim?, val concern: Claim?)
internal data class Intervention(val question: Claim, val proposal: Claim?)
internal data class Analysis(val views: List<SpeakerView>, val commonGround: Claim?, val intervention: Intervention?)
internal data class Agreement(val proposal: Claim, val acceptedBy: Set<Speaker> = emptySet()) {
    val complete get() = acceptedBy.containsAll(Speaker.entries)
}

/** Pure session transitions. Native callbacks never mutate these values directly. */
internal data class Conversation(
    val revision: Long = 0,
    val active: Boolean = false,
    val names: Map<Speaker, String> = mapOf(Speaker.A to "Alex", Speaker.B to "Sam"),
    val turns: List<Turn> = emptyList(),
    val recording: Speaker? = null,
    val analysis: Analysis? = null,
    val analysisTurns: List<Turn> = emptyList(),
    val pending: Intervention? = null,
    val suggestionOpen: Boolean = false,
    val agreement: Agreement? = null,
    val pendingChimed: Boolean = false,
    val lastChimeAt: Long? = null,
) {
    fun start(a: String, b: String) = Conversation(
        revision = revision + 1, active = true,
        names = mapOf(Speaker.A to a.trim().ifEmpty { "Alex" }, Speaker.B to b.trim().ifEmpty { "Sam" }),
    )

    fun beginTurn(speaker: Speaker): Conversation {
        require(active && turns.size < 8 && recording == null)
        return copy(revision = revision + 1, recording = speaker, pending = null,
            suggestionOpen = false, pendingChimed = false, agreement = agreement?.takeIf { it.complete })
    }

    fun stopRecording() = copy(recording = null)

    fun commit(speaker: Speaker, text: String, typed: Boolean = false): Conversation {
        if (text.isBlank()) return this
        require(active && turns.size < 8 && text.length <= 1000) { "Use up to eight turns of at most 1,000 characters." }
        return copy(revision = revision + 1, recording = null,
            turns = turns + Turn((turns.lastOrNull()?.id ?: 0) + 1, speaker, text.trim(), typed),
            pending = null, suggestionOpen = false, agreement = agreement?.takeIf { it.complete }, pendingChimed = false)
    }

    fun edit(id: Int, text: String): Conversation {
        require(active && turns.any { it.id == id } && text.isNotBlank() && text.length <= 1000)
        return copy(revision = revision + 1,
            turns = turns.map { if (it.id == id) it.copy(text = text.trim()) else it },
            pending = null, suggestionOpen = false, agreement = null, pendingChimed = false)
    }

    fun apply(requestRevision: Long, result: Analysis, now: Long): Conversation {
        if (!active || revision != requestRevision || recording != null) return this
        val intervention = result.intervention.takeIf { turns.map { it.speaker }.toSet().size == 2 }
        return copy(analysis = result, analysisTurns = turns, pending = intervention, suggestionOpen = false,
            agreement = if (intervention?.proposal == null) agreement?.takeIf { it.complete }
                else agreement?.takeIf { it.proposal == intervention.proposal }, pendingChimed = false)
    }

    fun chimeDue(now: Long) = active && recording == null && pending != null && !suggestionOpen && !pendingChimed &&
        (lastChimeAt == null || now - lastChimeAt >= 60_000)

    fun markChimed(now: Long) = if (chimeDue(now)) copy(lastChimeAt = now, pendingChimed = true) else this
    fun openSuggestion() = copy(suggestionOpen = pending != null)
    fun dismissSuggestion() = copy(pending = null, suggestionOpen = false, agreement = agreement?.takeIf { it.complete })

    fun confirm(speaker: Speaker): Conversation {
        val proposal = pending?.proposal ?: return this
        if (!suggestionOpen) return this
        val current = agreement?.takeIf { it.proposal == proposal } ?: Agreement(proposal)
        return copy(agreement = current.copy(acceptedBy = current.acceptedBy + speaker))
    }

    fun end() = Conversation(revision = revision + 1, names = names)
}
