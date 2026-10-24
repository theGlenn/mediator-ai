# Mediator

An Android phone between two people: local speech recognition, a shared conversation map, and an occasional question people choose whether to open. Built for the 2026-10-24 Gemma Hardware Hackathon.

The app uses Gemma 4 E2B through LiteRT-LM 0.18.0 and Whisper Tiny English through Xybrid Kotlin 0.13.1. All inference happens on the phone. It has no network permission, account, API key, analytics exporter, or conversation persistence. Model files are staged by USB.

## Build and provision

Use JDK **21**, Android SDK **36**, Python 3, and Android platform-tools (`adb`). Set `JAVA_HOME` and `ANDROID_HOME` for your machine. The Gradle wrapper downloads its pinned build tools on the development host.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 scripts/verify_apk.py
python3 scripts/provision.py --download-only
# Connect an arm64 Android 12+ phone, unlock it, and authorize USB debugging.
python3 scripts/provision.py
```

Provisioning verifies SHA-256 and exact sizes against `app/src/main/assets/models.lock.json`, installs the debug APK with `adb install -r`, and pushes approximately 2.62 GB to the app's external files directory. It also gives the app access to model directories created by the USB shell; otherwise some phones report missing models despite successful transfers. Android's parent app-specific directory retains scoped-storage isolation. Models are Git-ignored. Use `--serial DEVICE_SERIAL` when multiple phones are attached. `--verify-only` checks already downloaded host files without network access.

On the phone, tap **Load and check models**. The app checks both model hashes again, warms Whisper, and checks constrained Gemma generation. It tries GPU first, then CPU if GPU initialization or warm-up fails; **Device check** shows the successful backend. Start stays disabled until both runtimes pass their warm-up. Keep the application ID fixed; uninstalling erases the staged models.

The target is the **OnePlus 13**. The APK includes only arm64 native libraries. Phone performance, microphone quality, and sustained runtime stability require the device gate below; a successful APK build does not establish those properties.

## Two-minute demonstration

1. Turn on airplane mode, turn Wi-Fi off, and mirror the phone over wired USB if desired. Choose notification volume before the demo. The chime uses Android's notification stream and respects system sound settings.
2. Enter **Alex** and **Sam**. Start, then lay the phone flat between them. Alex's half faces across the table; Sam's half faces the other way. Tap **Speak · 30s** on your own side; use **Done speaking** after roughly 10 seconds to keep the demonstration short. The active side is outlined and shows its timer and live transcript.
3. Alex: “I need a regular update because I'm responsible for delivery.”
4. Sam: “I want to deliver on time too. Constant check-ins break my focus.”
5. Alex: “I don't need meetings all day. One reliable update would help me plan.”
6. Sam: “A short update at four would work better than interruptions.”
7. Tap **Debug** to see the conversation map, transcript, corrections, manual entry and device details. Tap a request or concern to reveal the source quote. Concerns and common ground are visibly tentative. Use **Correct** if transcription misheard a turn. **Back to conversation** returns to the tabletop without resetting it.
8. When the phone chimes, tap the center orb. Both sides show the question in their own reading direction. Discuss it. If a concrete next step is offered and works for both, each person separately taps **Works for me** on their own side. Only then does an agreement appear. **Let it pass** dismisses the question.
9. **End** (or **End session** in Debug) clears the conversation and keeps the models warm for the next demonstration. Android Back returns from Debug to the tabletop; from the tabletop it ends the session.

The lines are prompts for live input, not prerecorded outputs. Gemma generates the map and suggestion each time. **Type a turn instead · manual entry** is the labeled recovery path for venue noise. It is never presented as microphone capture.

## Validation

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python3 scripts/verify_apk.py
# Real Gemma, same prompt/schema/decoder, desktop CPU diagnostic:
./gradlew :probe:run
# Optional one-case diagnostic, also prints an unconstrained extraction check:
./gradlew :probe:run --args=--smoke-only
# Actual phone; provision first and use airplane mode. Keep the target installed:
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class ai.xybrid.mediator.DeviceGateTest ai.xybrid.mediator.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/ai.xybrid.mediator/files/evaluation-report.json
```

Unit tests cover evidence ownership, unknown sources, malformed output, session revisions, corrections, the eight-turn limit, chime suppression/cooldown, two distinct confirmations, and repeated cancellation while native cleanup is still draining. `verify_apk.py` inspects the packaged manifest, including dependency contributions, and fails if network permissions appear.

### Live timing diagnostics

**Debug → Device check** shows timings for the latest successful analysis and a history of up to 20 completed analyses in this session. Recorded turns separate time to the first nonblank partial (from capture start, including any initial silence), capture duration, ASR finalization after **Done**, Gemma analysis, and audio end to the map being applied. The last measure includes ASR cleanup and analysis, but does not measure display rendering. Typed turns, edits and retries have only an analysis duration. Session start/end clear this in-memory history.

Successful analyses also emit timing/count metadata to Android logcat, with no transcript, names, quotes, prompts or audio:

```sh
adb logcat -d -v time -s MediatorPerf:I '*:S'
```

No live timing log existed in the original POC, so these samples start after installing the tabletop follow-up. The live round reported by the tester had no saved transcript or per-turn timings; its retained Android logs did not yield inference measurements. `evaluation-report.json` remains synthetic-only. A new timing sample is emitted only after a successful current-session analysis; silence, cancelled work and failures do not produce completed samples.

The [host run](docs/mediator-host-evaluation.json) and [OnePlus run](docs/mediator-device-evaluation.json) each pass ten automatic cases. On OnePlus CPH2655 / Android 16, Gemma uses GPU and analysis takes 9.14–12.37 seconds (median 10.76 seconds), exceeding ten seconds in 9/10 cases. The [handoff](docs/mediator-poc.md) records the native gate and remaining semantic findings. Live microphone behavior, human usefulness and airplane-mode rehearsals remain unverified; the latency target is not met.

The direct instrumentation command leaves the app, models and report installed. `./gradlew :app:connectedDebugAndroidTest` is also available; the Gradle configuration enables `android.injected.androidTest.leaveApksInstalledAfterRun` because default Gradle cleanup removes the app and its external files. Use `adb -s DEVICE_SERIAL` for each device command when more than one phone is attached.

The device test loads **both real runtimes**, runs the ten bundled synthetic dialogues, checks that their explicit requests were extracted, and writes `evaluation-report.json`. It fails if models are absent; it does not skip the gate or substitute model output. The desktop probe writes `probe/build/evaluation-report.json`. **Desktop timing is not phone timing.** These reports contain only synthetic fixtures; live sessions are never written to either report.

For each synthetic dialogue, a person must review the supplied rubric and mark whether the question is useful. Require correct attribution throughout and at least **8/10 useful questions**. Source-ID validation alone cannot establish semantic faithfulness. Aim for five seconds from final transcript to map/card, and require results within ten seconds in three consecutive live rehearsals. Record the actual device, backend, timings and results in the [hackathon handoff](docs/mediator-poc.md).

Also check microphone denial, a silent turn, backgrounding while recording, backgrounding during inference, resetting during inference, an edited transcript, a long turn, and starting a new turn while a suggestion is pending. A new turn clears the suggestion. Backgrounding stops capture and cancels inference. No question is offered before both people speak; chimes occur only outside recording and at most once per minute.

## Structure and limits

- `Conversation` owns session transitions and agreement state. Native callbacks carry a revision and cannot revive an ended or corrected session. A failed correction keeps the previous map and its original source transcript, visibly labeled until a new analysis succeeds. Completed agreements survive backgrounding and ordinary follow-up turns; editing a source, changing the proposal or ending the session invalidates them.
- `GemmaAnalysis` owns the bounded prompt and native generation configuration. `AnalysisCodec` enforces schema shape, source IDs, and speaker ownership. No model-produced graph IDs or agreement flags are accepted.
- `LocalRuntime` owns engine loading, checksums, warm-up, cancellation and teardown. All blocking work runs off the UI thread.
- `Microphone` feeds 16 kHz mono Float32 PCM to Xybrid; partials replace the visible transcript because they are cumulative. A simple energy gate rejects silent turns after capture; audio is still fed to ASR during the turn. It does not separate background voices.
- `MediatorViewModel` serializes turns through `SessionWork` and controls the timer and chime. Cancellation waits for the actual native operation even after repeated End/background actions; queued ASR audio is aborted separately from normal turn finalization. The map layout is deterministic, with one lane per person and common ground in the center.
- `TabletopConversation` is the default face-to-face experience: opposing participant panels, explicit active/inactive state, central orb and per-person suggestion confirmations. The original map/transcript interface is available in Debug. `LatencyDiagnostics` retains bounded timing metadata and never retains conversation content.

The POC supports eight English turns of up to 1,000 characters, foreground use, and explicit speaker selection. It has no diarization, background service, speech output, cloud fallback, ADK dependency, database, or user-session export. Concerns remain tentative; each person can inspect their source and correct the transcript.

`./clean.sh` lists ignored build output; `./clean.sh --apply` removes it. It leaves downloaded models intact. Source is Apache-2.0; model weights and third-party libraries retain their upstream licenses and are not committed here.
