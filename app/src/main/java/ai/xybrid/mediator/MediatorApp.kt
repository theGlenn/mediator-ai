package ai.xybrid.mediator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Ink = Color(0xFF20372F)
private val Paper = Color(0xFFF7F6F0)
private val Green = Color(0xFF356A54)
private val AColor = Color(0xFFE8EFF8)
private val BColor = Color(0xFFF7EBDD)

@Composable
internal fun MediatorApp(model: MediatorViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var evidence by remember { mutableStateOf<Claim?>(null) }
    var evidenceFrom by remember { mutableStateOf<Speaker?>(null) }
    var edit by remember { mutableStateOf<Turn?>(null) }
    var typed by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    var requestedSpeaker by remember { mutableStateOf(Speaker.A) }
    var debugMode by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) model.record(requestedSpeaker) else permissionError = true
    }
    val onRecord: (Speaker) -> Unit = { speaker ->
        requestedSpeaker = speaker
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            model.record(speaker) else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val showDebugEvidence: (Claim) -> Unit = { evidenceFrom = null; evidence = it }
    LaunchedEffect(state.conversation.active) { if (!state.conversation.active) debugMode = false }
    BackHandler(enabled = state.conversation.active) { if (debugMode) debugMode = false else model.end() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Paper,
        surface = Color.White, onSurface = Ink, onBackground = Ink)) {
        Scaffold(containerColor = Paper, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
            if (state.conversation.active && !debugMode) {
                TabletopConversation(state, Modifier.padding(insets), onRecord, model::done,
                    model::openSuggestion, model::dismissSuggestion, model::confirm,
                    onEvidence = { speaker, claim -> evidenceFrom = speaker; evidence = claim },
                    onDebug = { debugMode = true }, onEnd = model::end)
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Header(state.conversation.active) { model.end() } }
                    if (!state.conversation.active) {
                        item { Setup(state, model::loadModels, model::start) }
                    } else {
                        item { OutlinedButton(onClick = { debugMode = false }, modifier = Modifier.fillMaxWidth()) { Text("Back to conversation") } }
                        item {
                            SpeakingControls(state, onRecord, onDone = model::done, onTyped = { typed = true })
                        }
                        item { Status(state, onRetry = {
                            val speaker = state.retrySpeaker
                            if (speaker != null) onRecord(speaker) else model.retry()
                        }, retryLabel = if (state.retrySpeaker != null) "Record again" else "Retry analysis") }
                        item { ConversationMap(state.conversation, showDebugEvidence) }
                        if (state.conversation.pending != null) {
                            item { Suggestion(state.conversation, model::openSuggestion, model::dismissSuggestion, model::confirm, showDebugEvidence) }
                        }
                        item { Text("THE CONVERSATION", style = MaterialTheme.typography.labelMedium, color = Green) }
                        items(state.conversation.turns, key = { it.id }) { turn ->
                            TurnCard(turn, state.conversation.names.getValue(turn.speaker),
                                enabled = !state.busy && state.conversation.recording == null) { edit = turn }
                        }
                        item { DeviceDetails(state) }
                    }
                }
            }
        }
        evidence?.let { claim -> EvidenceDialog(claim, state.conversation,
            Modifier.rotate(if (evidenceFrom == Speaker.A) 180f else 0f)) { evidence = null } }
        edit?.let { turn ->
            TextEntryDialog("Correct ${state.conversation.names[turn.speaker]}'s turn", turn.text,
                onDismiss = { edit = null }, onSave = { model.edit(turn.id, it); edit = null })
        }
        if (typed) TypedTurnDialog(state.conversation.names, { typed = false }) { speaker, text ->
            model.typed(speaker, text); typed = false
        }
        if (permissionError) AlertDialog(onDismissRequest = { permissionError = false },
            title = { Text("Microphone access is needed") },
            text = { Text("Allow microphone access in Android Settings to record a turn. You can also continue with the clearly labeled typed-entry option.") },
            confirmButton = { TextButton(onClick = { permissionError = false }) { Text("Got it") } })
    }
}

@Composable
private fun Header(active: Boolean, onEnd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (active) "mediator · debug" else "mediator", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("A little space to understand each other.", style = MaterialTheme.typography.bodySmall)
        }
        if (active) TextButton(onClick = onEnd) { Text("End session") }
        else Surface(color = Color(0xFFE5EDE5), shape = RoundedCornerShape(20.dp)) {
            Text("On this phone", Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Setup(state: ScreenState, onLoad: () -> Unit, onStart: (String, String) -> Unit) {
    var a by remember { mutableStateOf("Alex") }
    var b by remember { mutableStateOf("Sam") }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Two people.\nRoom for both perspectives.", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
        Text("Take turns speaking. See what matters to each of you. When a question might help, a gentle chime invites you to look.")
        OutlinedTextField(a, { a = it.take(30) }, label = { Text("First person's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(b, { b = it.take(30) }, label = { Text("Second person's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Your microphone and conversation stay on this phone. Ending the session clears the conversation.", style = MaterialTheme.typography.bodySmall)
        Status(state, onLoad)
        if (!state.ready) Button(onClick = onLoad, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Checking models…" else "Load and check models")
        } else Button(onClick = { onStart(a, b) }, enabled = !state.busy && a.isNotBlank() && b.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Start conversation") }
        DeviceDetails(state)
    }
}

@Composable
private fun SpeakingControls(state: ScreenState, onRecord: (Speaker) -> Unit, onDone: () -> Unit, onTyped: () -> Unit) {
    val session = state.conversation
    val recording = session.recording
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recording != null) {
            Text("${session.names[recording]}'s turn", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.seconds}s", fontSize = 48.sp, color = Green, modifier = Modifier.weight(1f))
                Button(onClick = onDone, enabled = !state.busy) { Text("Done speaking") }
            }
            LinearProgressIndicator(progress = { state.seconds / 30f }, modifier = Modifier.fillMaxWidth())
            Text(state.partial.ifEmpty { "Listening on this phone…" }, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("Who would like to speak?", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Speaker.entries.forEach { speaker ->
                    Button(onClick = { onRecord(speaker) }, enabled = !state.busy && session.turns.size < 8,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(
                            containerColor = if (speaker == Speaker.A) AColor else BColor, contentColor = Ink)) {
                        Text("${session.names[speaker]} · 30s")
                    }
                }
            }
            TextButton(onClick = onTyped, enabled = !state.busy && session.turns.size < 8) { Text("Type a turn instead · manual entry") }
            if (session.turns.size >= 8) Text("Eight-turn demo complete. You can correct turns, review a suggestion, or end the session.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Status(state: ScreenState, onRetry: () -> Unit, retryLabel: String = "Retry") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = Green)
        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(message)
                    TextButton(onClick = onRetry, enabled = !state.busy) { Text(retryLabel) }
                }
            }
        }
    }
}

@Composable
private fun ConversationMap(session: Conversation, onEvidence: (Claim) -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("THE CONVERSATION MAP", style = MaterialTheme.typography.labelMedium, color = Green)
        if (session.analysis != null && session.analysisTurns != session.turns) {
            Text("Previous map · waiting for updated analysis. Source links show the transcript used for this map.",
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Speaker.entries.forEach { speaker ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val view = session.analysis?.views?.find { it.speaker == speaker }
                    Text(session.names.getValue(speaker), fontWeight = FontWeight.SemiBold)
                    ClaimNode("REQUEST", view?.request, if (speaker == Speaker.A) AColor else BColor, "Listening for their request", onEvidence)
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        drawLine(Green.copy(alpha = 0.3f), Offset(size.width / 2, 0f),
                            Offset(size.width / 2, size.height), 2.dp.toPx())
                    }
                    ClaimNode("CONCERN · TENTATIVE", view?.concern, if (speaker == Speaker.A) AColor else BColor, "Not stated yet", onEvidence)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val color = Green.copy(alpha = 0.3f)
            drawLine(color, Offset(size.width * 0.25f, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            drawLine(color, Offset(size.width * 0.75f, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
        }
        ClaimNode("POSSIBLE COMMON GROUND · TENTATIVE", session.analysis?.commonGround,
            Color(0xFFE6EEE5), "A shared goal may emerge as you both speak.", onEvidence)
        session.agreement?.takeIf { it.complete }?.let {
            ClaimNode("AGREED BY BOTH", it.proposal, Color(0xFFD3E8D5), "", onEvidence)
        }
    }
}

@Composable
private fun ClaimNode(label: String, claim: Claim?, color: Color, empty: String, onEvidence: (Claim) -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        .clickable(enabled = claim != null) { claim?.let(onEvidence) }) {
        Column(Modifier.padding(14.dp).heightIn(min = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = Green)
            Text(claim?.text ?: empty, style = MaterialTheme.typography.bodyMedium)
            if (claim != null) Text("View source ↗", style = MaterialTheme.typography.labelSmall, color = Green)
        }
    }
}

@Composable
private fun Suggestion(session: Conversation, onOpen: () -> Unit, onDismiss: () -> Unit,
    onConfirm: (Speaker) -> Unit, onEvidence: (Claim) -> Unit) {
    val suggestion = session.pending ?: return
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A question, when you're ready", style = MaterialTheme.typography.titleMedium)
            if (!session.suggestionOpen) {
                Text("You choose whether to bring it into the conversation.")
                Button(onClick = onOpen) { Text("Show suggestion") }
            } else {
                Text(suggestion.question.text, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onEvidence(suggestion.question) }) { Text("What this is based on") }
                suggestion.proposal?.let { proposal ->
                    Text("POSSIBLE NEXT STEP", style = MaterialTheme.typography.labelSmall, color = Green)
                    Text(proposal.text, Modifier.clickable { onEvidence(proposal) })
                    TextButton(onClick = { onEvidence(proposal) }) { Text("View next-step sources") }
                    Speaker.entries.forEach { speaker ->
                        val accepted = session.agreement?.acceptedBy?.contains(speaker) == true
                        OutlinedButton(onClick = { onConfirm(speaker) }, enabled = !accepted, modifier = Modifier.fillMaxWidth()) {
                            Text("${session.names[speaker]}: ${if (accepted) "Confirmed ✓" else "Works for me"}")
                        }
                    }
                    if (session.agreement?.complete != true) Text("It becomes an agreement only when both of you confirm.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onDismiss) { Text("Let it pass") }
        }
    }
}

@Composable
private fun TurnCard(turn: Turn, name: String, enabled: Boolean, onEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$name · Turn ${turn.id}${if (turn.typed) " · Typed entry" else ""}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = onEdit, enabled = enabled) { Text("Correct") }
            }
            Text(turn.text)
        }
    }
}

@Composable
private fun DeviceDetails(state: ScreenState) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide device check" else "Device check") }
        if (expanded) {
            Text(state.backend.ifEmpty { "Gemma and Whisper not loaded yet." }, style = MaterialTheme.typography.bodySmall)
            if (state.ready) Text("Whisper Tiny English · Xybrid CPU · 10 s encoder context · both models warmed", style = MaterialTheme.typography.bodySmall)
            state.analysisMillis?.let { Text("Analysis: ${it / 1000.0}s", style = MaterialTheme.typography.bodySmall) }
            state.endToCardMillis?.let { Text("Audio end to map/card: ${it / 1000.0}s", style = MaterialTheme.typography.bodySmall) }
            state.diagnostics.lastOrNull()?.let { timing ->
                timing.firstPartialMillis?.let { Text("First words after capture starts: ${it / 1000.0}s", style = MaterialTheme.typography.bodySmall) }
                timing.captureMillis?.let { Text("Audio captured: ${it / 1000.0}s", style = MaterialTheme.typography.bodySmall) }
                timing.finalTranscriptMillis?.let { Text("ASR after Done: ${it / 1000.0}s", style = MaterialTheme.typography.bodySmall) }
                timing.gemma?.let { gemma -> Text(buildString {
                    append("Gemma setup ${gemma.setupMillis / 1000.0}s · generation ${gemma.generateMillis / 1000.0}s")
                    gemma.prefillTokens?.let { append(" · $it prompt tokens") }
                    gemma.timeToFirstTokenMillis?.let { append(" · first token ${it / 1000.0}s") }
                    gemma.decodeTokens?.let { append(" · $it output tokens") }
                    gemma.decodeTokensPerSecond?.let { append(" at %.1f tok/s".format(java.util.Locale.ROOT, it)) }
                }, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.diagnostics.isNotEmpty()) {
                Text("THIS SESSION · COMPLETED ANALYSES", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall)
                state.diagnostics.forEachIndexed { index, timing ->
                    Text(buildString {
                        append("${index + 1}. ${timing.source.name.lowercase()} · ${timing.turnCount} turns · Gemma ${timing.analysisMillis / 1000.0}s")
                        timing.finalTranscriptMillis?.let { append(" · ASR finish ${it / 1000.0}s") }
                        timing.endToMapMillis?.let { append(" · end to map ${it / 1000.0}s") }
                        timing.gemma?.decodeTokens?.let { append(" · $it output tokens") }
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("No network permission. No conversation saved to disk.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EvidenceDialog(claim: Claim, session: Conversation, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = modifier, title = { Text("What this is based on") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(claim.text, fontWeight = FontWeight.SemiBold)
            claim.evidence.forEach { id -> session.analysisTurns.find { it.id == id }?.let {
                Text("${session.names[it.speaker]} · Turn $id\n“${it.text}”")
            } }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(1000) }, minLines = 3, maxLines = 6,
            label = { Text("Transcript") }, supportingText = { Text("${text.length}/1000") }) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Update map") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TypedTurnDialog(names: Map<Speaker, String>, onDismiss: () -> Unit, onSave: (Speaker, String) -> Unit) {
    var speaker by remember { mutableStateOf(Speaker.A) }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Typed turn · manual entry") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This turn will be labeled as typed rather than recorded.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Speaker.entries.forEach { person -> FilterChip(selected = speaker == person,
                    onClick = { speaker = person }, label = { Text(names.getValue(person)) }) }
            }
            OutlinedTextField(text, { text = it.take(1000) }, minLines = 3, maxLines = 6, label = { Text("What would you like to say?") })
        } },
        confirmButton = { TextButton(onClick = { onSave(speaker, text) }, enabled = text.isNotBlank()) { Text("Add turn") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
