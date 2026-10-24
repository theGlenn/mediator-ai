package ai.xybrid.mediator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TableInk = Color(0xFF20372F)
private val TableGreen = Color(0xFF356A54)

/** The phone lies between two people; the upper side faces the person across the table. */
@Composable
internal fun TabletopConversation(
    state: ScreenState,
    modifier: Modifier = Modifier,
    onRecord: (Speaker) -> Unit,
    onDone: () -> Unit,
    onOpenSuggestion: () -> Unit,
    onDismissSuggestion: () -> Unit,
    onConfirm: (Speaker) -> Unit,
    onEvidence: (Speaker, Claim) -> Unit,
    onDebug: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        ParticipantSide(state, Speaker.A, Modifier.weight(1f).rotate(180f),
            onRecord, onDone, onConfirm, { onEvidence(Speaker.A, it) })
        MediatorCenter(state, onOpenSuggestion, onDismissSuggestion, onDebug, onEnd)
        ParticipantSide(state, Speaker.B, Modifier.weight(1f),
            onRecord, onDone, onConfirm, { onEvidence(Speaker.B, it) })
    }
}

@Composable
private fun ParticipantSide(
    state: ScreenState,
    speaker: Speaker,
    modifier: Modifier,
    onRecord: (Speaker) -> Unit,
    onDone: () -> Unit,
    onConfirm: (Speaker) -> Unit,
    onEvidence: (Claim) -> Unit,
) {
    val session = state.conversation
    val active = session.recording == speaker
    val canSpeak = state.ready && !state.busy && session.recording == null && session.turns.size < 8
    val background = if (speaker == Speaker.A) Color(0xFFE8EFF8) else Color(0xFFF7EBDD)
    Surface(modifier.fillMaxWidth().semantics {
        stateDescription = if (active) "Active speaker" else "Inactive speaker"
    }, shape = RoundedCornerShape(24.dp), color = background.copy(alpha = if (active) 1f else 0.6f),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) TableGreen else TableInk.copy(alpha = 0.08f))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(session.names.getValue(speaker), fontSize = 28.sp, fontWeight = FontWeight.Medium,
                color = TableInk, textAlign = TextAlign.Center)
            val label = when {
                active && state.busy -> "Finishing your words…"
                active -> "Listening · ${state.seconds}s"
                session.recording != null -> "${session.names[session.recording]} is speaking"
                state.busy -> "A moment to reflect…"
                session.turns.size >= 8 -> "Time to reflect together"
                else -> "Space for your perspective"
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = TableGreen, textAlign = TextAlign.Center)
            if (active) {
                LinearProgressIndicator(progress = { state.seconds / 30f }, modifier = Modifier.fillMaxWidth())
                Text(state.partial.ifBlank { "Take your time. I'm listening." }, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            val suggestion = session.pending.takeIf { session.suggestionOpen }
            if (suggestion != null) {
                ParticipantSuggestion(session, speaker, suggestion, onConfirm, onEvidence)
            } else if (!active) {
                val agreement = session.agreement?.takeIf { it.complete }
                if (agreement != null) {
                    Text("Agreed next step", style = MaterialTheme.typography.labelMedium, color = TableGreen)
                    Text(agreement.proposal.text, Modifier.clickable { onEvidence(agreement.proposal) },
                        textAlign = TextAlign.Center)
                } else {
                    session.turns.lastOrNull { it.speaker == speaker }?.let { turn ->
                        Text(turn.text, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = TableInk.copy(alpha = 0.7f))
                    }
                }
            }
            if (active) {
                Button(onClick = onDone, enabled = !state.busy) { Text("Done speaking") }
            } else {
                OutlinedButton(onClick = { onRecord(speaker) }, enabled = canSpeak,
                    modifier = Modifier.semantics { contentDescription = "${session.names[speaker]}: speak for up to thirty seconds" }) {
                    Text("Speak · 30s")
                }
            }
        }
    }
}

@Composable
private fun ParticipantSuggestion(session: Conversation, speaker: Speaker, suggestion: Intervention,
    onConfirm: (Speaker) -> Unit, onEvidence: (Claim) -> Unit) {
    Text(suggestion.question.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    TextButton(onClick = { onEvidence(suggestion.question) }) { Text("What this is based on") }
    suggestion.proposal?.let { proposal ->
        Text("Possible next step", style = MaterialTheme.typography.labelMedium, color = TableGreen)
        Text(proposal.text, Modifier.clickable { onEvidence(proposal) }, textAlign = TextAlign.Center)
        TextButton(onClick = { onEvidence(proposal) }) { Text("View next-step sources") }
        val accepted = session.agreement?.acceptedBy?.contains(speaker) == true
        Button(onClick = { onConfirm(speaker) }, enabled = !accepted,
            modifier = Modifier.semantics { contentDescription = "${session.names[speaker]}: ${if (accepted) "confirmed" else "works for me"}" }) {
            Text(if (accepted) "Confirmed ✓" else "Works for me")
        }
        Text(if (session.agreement?.complete == true) "Agreed by both of you" else "Both of you decide, separately.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MediatorCenter(state: ScreenState, onOpen: () -> Unit, onDismiss: () -> Unit,
    onDebug: () -> Unit, onEnd: () -> Unit) {
    val session = state.conversation
    val available = session.pending != null
    val label = when {
        state.error != null -> "Needs attention · open Debug"
        session.recording != null && !state.busy -> "Listening"
        state.busy -> "Reflecting"
        available && !session.suggestionOpen -> "A question is ready · tap the orb"
        available -> "A question to consider together"
        else -> state.status
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, Modifier.rotate(180f), style = MaterialTheme.typography.labelMedium, color = TableGreen,
            textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDebug, modifier = Modifier.widthIn(min = 88.dp)) { Text("Debug") }
            FilledTonalIconButton(onClick = onOpen, enabled = available && !session.suggestionOpen && !state.busy,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Mediator. $label" }, shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Transparent, disabledContainerColor = Color.Transparent)) {
                Box(Modifier.size(56.dp).background(Brush.radialGradient(listOf(
                    Color(0xFFE8F5D8), if (available) Color(0xFF77AB65) else Color(0xFF86A69E), TableGreen)), CircleShape))
            }
            if (available && session.suggestionOpen) {
                TextButton(onClick = onDismiss, modifier = Modifier.widthIn(min = 88.dp)) { Text("Let it pass") }
            } else {
                TextButton(onClick = onEnd, modifier = Modifier.widthIn(min = 88.dp)) { Text("End") }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = TableGreen, textAlign = TextAlign.Center)
    }
}
