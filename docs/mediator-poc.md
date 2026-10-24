# Mediator — implementation and device handoff

**Selected 2026-10-24.** Standalone Kotlin/Jetpack Compose POC for two people to surface their requests, concerns, and possible common ground on an Android phone. It listens during explicit timed turns; a chime invites people to open a suggested question. A next step becomes an agreement only after both participants confirm.

Source and commands: [the app README](../README.md).

## Implemented

- Gemma 4 E2B through LiteRT-LM 0.18.0, GPU first with CPU fallback; Whisper Tiny English through published Xybrid Kotlin 0.13.1. No SDK source changes or ADK adapter.
- USB provisioning with frozen Hugging Face revisions, exact sizes and SHA-256; checks run on the host and phone. The APK contains neither network permission nor a model downloader.
- Two names, 30-second recording turns with cumulative live transcript, deterministic map, evidence quotes, transcript corrections, typed-entry recovery, a bounded JSON analysis call, and dual confirmation.
- Eight-turn cap, stale-result rejection, recording cancellation on backgrounding, session reset, one pending intervention, and a 60-second chime cooldown.
- Unit tests, packaged-manifest check, ten synthetic evaluation dialogues, a real-model desktop probe, and a real-runtime Android instrumentation gate.

## Validation record

| Check | Evidence / status |
|---|---|
| Android APK build | `:app:assembleDebug` succeeds with both native runtimes packaged; no duplicate native library conflict. |
| Unit tests | **21 tests pass:** session policy, structured output, snapshot evidence, agreement persistence and repeated cancellation during native cleanup. |
| Android lint | `:app:lintDebug` passes; version/target-ABI notices are not device results. |
| Offline APK manifest | Packaged permission inspection confirms microphone permission and no `INTERNET` or `ACCESS_NETWORK_STATE`. |
| Model provisioning | Both pinned weight downloads verified on the VM and Mac, then staged over USB. Gemma on Mac: `<mac build dir>/mediator-android/models/gemma-4-E2B-it.litertlm`. |
| Real Gemma quality | **10/10 automatic checks pass** with real Gemma on VM CPU: requests, question, source ownership, one-speaker suppression, corrected request evidence and absent unknown concerns. [Full synthetic report](mediator-host-evaluation.json). Human semantic/usefulness review remains open. |
| Mac build / USB install | **Passed** on Apple Silicon with Corretto 21.0.13 and Android SDK 36. `assembleDebug`, 21 unit tests, lint and instrumentation APK build passed in 6m31s on the first build. Offline manifest verified; APK installed and launched on CPH2655, Android 16 / API 36. |
| OnePlus native runtime gate | **Passed:** Whisper warm-up and all ten real Gemma automatic cases in the same Android process. Final retained run: 1 instrumentation test / 0 failures (129.024 s total), **Gemma GPU**. [Synthetic device report](mediator-device-evaluation.json). App and models remain installed; live microphone use remains untested. |
| Phone analysis latency | **9.14–12.37 s**, median **10.76 s**, across ten synthetic dialogues on Gemma GPU. Only 1/10 is within ten seconds. Five-second target not met; audio-end-to-card and live rehearsal latency remain unmeasured. |
| Human quality review | **Pending.** Review all ten synthetic dialogues; require at least 8 useful questions and correct attribution throughout. |
| Live demo rehearsals | **Pending.** Three consecutive four-turn sessions in airplane mode, with results within ten seconds of finalized input. |

A successful build alone does not establish native compatibility, human usefulness, or phone latency. The native gate above is a separate real-device test. The app's **Device check** displays the backend and observed timings. The phone test writes a synthetic `evaluation-report.json` for review; live conversation content remains in memory.

## Host model observations

The final production prompt/schema/decoder ran on LiteRT-LM JVM 0.18.0, JDK 21, an x86_64 cloud VM (8 visible Intel Xeon CPUs), and the pinned Gemma bundle. Analysis took **11.61–13.08 seconds**, median **12.30 seconds**. These are host CPU measurements, not OnePlus measurements or evidence that the phone latency target passed.

The automatic checks establish response shape and source ownership. They do **not** establish full semantic faithfulness or the 8/10 human usefulness threshold. Inspection found shared-topic labels used as common ground in the budget/weekend cases, a timing question that repeats supplied information, and an `N/A` concern label in the weekend case. The parser now normalizes such unknown sentinel labels to absence; a regression unit test covers that follow-up. The saved report preserves the original measured outputs and records both decoder hashes. Keep the quality gate open and review these before presenting the app as reliable mediation. The map labels concerns/common ground as tentative and exposes source quotes; failed decoding retains the previous map and its original transcript snapshot.

The Mac build copy is at `<mac build dir>/mediator-android`. It uses task-local Corretto 21.0.13 because the registered Java is 11 and Android Studio bundles Java 25. The first Mac build finished successfully in 6m31s: APK, 21 unit tests, lint, instrumentation APK and packaged offline manifest all passed. USB install and launch succeeded on CPH2655 / Android 16 / API 36. APK SHA-256: `648a555150d7cfd6afdfd369ffc13f2f103ba1f06521d5c1de4fc5caf06bbe18`.

Both pinned models match their host SHA-256 checksums and are staged on the phone. Two real native gate runs passed; the final direct instrumentation run retained the synthetic report and relaunched the app. Provisioning now repairs shell-created model directory permissions: this phone defaults them to shell-owned `2770`, which prevented app access despite successful USB transfers. The change affects only the model subdirectories inside Android app-specific storage. Default Gradle test cleanup also uninstalled the target app and removed external files; the retention flag is now enabled, and the documented direct instrumentation path avoids that cleanup.

## Phone handoff

1. Build and run the host checks from the app README. Connect an unlocked arm64 phone with USB debugging authorized.
2. Run `python3 scripts/provision.py`. Keep the application ID `ai.xybrid.mediator` and update with `adb install -r` so models survive.
3. Enable airplane mode, disable Wi-Fi, and run **Load and check models**. Record the phone model, Android version and selected backend.
4. Install the instrumentation APK and run the README's direct `adb shell am instrument` command; pull and review the synthetic report. This leaves the app and models installed.
5. Rehearse the two-minute script in the README. Check that the pending question clears on a new turn, editing never applies a stale result, and one person cannot create an agreement.
6. Record actual device timings, quality ratings, rehearsal results and any blocker here before submitting.

## Measured device result

OnePlus CPH2655, Android 16 / API 36, 4,096-byte pages. Gemma 4 E2B uses **GPU**. The final real native test passed in **129.024 seconds** and extracted both requests plus a question in all ten synthetic dialogues, with valid evidence ownership. Median analysis is **10.758 seconds** (range 9.144–12.371 seconds); nine cases exceed the ten-second rehearsal requirement. [Full measured outputs and source hashes](mediator-device-evaluation.json).

The native compatibility gate is clear. Latency needs improvement or an explicitly revised acceptance target before calling the demo rehearsal-ready. Live speech accuracy, audio-end-to-card timing, human usefulness ratings and three airplane-mode rehearsals remain open. The test warmed Whisper with silence; it did not record the microphone or change phone connectivity.

## Sources

- [LiteRT-LM Kotlin API, pinned v0.18.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md)
- [Gemma bundle, frozen revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/1caa527c1e88b55f046fcd8d2b4265606680663d)
- [Whisper Tiny English Q5_1, frozen revision](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1)
- [Xybrid Kotlin 0.13.1 Maven artifact](https://repo.maven.apache.org/maven2/ai/xybrid/xybrid-kotlin/0.13.1/)
- [ADK 1.4.0 / LiteRT-LM 0.18 compatibility issue](https://github.com/google/adk-kotlin/issues/738): ADK is outside this POC's dependency graph.
