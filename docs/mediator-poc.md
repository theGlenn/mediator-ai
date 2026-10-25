# Mediator — implementation and device handoff

**Selected 2026-10-24.** Standalone Kotlin/Jetpack Compose POC for two people to surface their requests, concerns, and possible common ground on an Android phone. It listens during explicit timed turns; a chime invites people to open a suggested question. A next step becomes an agreement only after both participants confirm.

Source and commands: [the app README](../README.md).

## Implemented

- Gemma 4 E2B through LiteRT-LM 0.18.0, GPU first with CPU fallback; Whisper Tiny English through published Xybrid Kotlin 0.13.1. No SDK source changes or ADK adapter.
- USB provisioning with frozen Hugging Face revisions, exact sizes and SHA-256; checks run on the host and phone. The APK contains neither network permission nor a model downloader.
- Two names, 30-second recording turns with cumulative live transcript, an incrementally built conversation map, evidence quotes, transcript corrections, typed-entry recovery, a bounded JSON analysis call, and dual confirmation.
- 40-turn cap (eight before the incremental map), stale-result rejection, recording cancellation on backgrounding, session reset, one pending intervention, and a 60-second chime cooldown.
- Unit tests, packaged-manifest check, ten synthetic evaluation dialogues, a real-model desktop probe, and a real-runtime Android instrumentation gate.
- Tabletop follow-up: opposing participant panels with the upper half rotated 180°, a central suggestion orb, and one confirmation control per participant. The original graph, transcript, corrections, manual entry and device checks are available in **Debug**. Suggestions remain optional, with the existing chime/cooldown policy and no speech output.
- Bounded live timing diagnostics: first nonblank partial, capture duration, ASR finalization, Gemma analysis and audio end to map application. Debug shows up to 20 completed analyses; `MediatorPerf` logcat entries contain only counts/timings. No transcript or audio persistence was added.
- Latency follow-up: Whisper encoder context 500 frames; compact Gemma output with implied citations, no whitespace and label guards; background conversation prefetch; LiteRT-LM benchmark counters in diagnostics; an explicit `LatencyProbeTest` instrumentation class. See [Latency investigation](#latency-investigation).

## Validation record

| Check | Evidence / status |
|---|---|
| Android APK build | `:app:assembleDebug` succeeds with both native runtimes packaged; no duplicate native library conflict. |
| Unit tests | **33 host tests pass:** session, graph, codec (incremental map updates), cancellation and timing checks. |
| Android lint | `:app:lintDebug` passes; version/target-ABI notices are not device results. |
| Offline APK manifest | Packaged permission inspection confirms microphone permission and no `INTERNET` or `ACCESS_NETWORK_STATE`. |
| Model provisioning | Both pinned weight downloads verified on the VM and Mac, then staged over USB. Gemma on Mac: `<mac build dir>/mediator-android/models/gemma-4-E2B-it.litertlm`. |
| Real Gemma quality | **10/10 automatic checks pass** with real Gemma on VM CPU: requests, question, source ownership, one-speaker suppression, corrected request evidence and absent unknown concerns. [Full synthetic report](mediator-host-evaluation.json). Human semantic/usefulness review remains open. |
| Mac build / USB install | **Passed** on Apple Silicon with Corretto 21.0.13 and Android SDK 36. `assembleDebug`, 21 unit tests, lint and instrumentation APK build passed in 6m31s on the first build. Offline manifest verified; APK installed and launched on CPH2655, Android 16 / API 36. |
| OnePlus native runtime gate | **Passed:** Whisper warm-up and all ten real Gemma automatic cases in the same Android process. Final retained run: 1 instrumentation test / 0 failures (129.024 s total), **Gemma GPU**. [Synthetic device report](mediator-device-evaluation.json). App and models remain installed; live microphone use remains untested. |
| Incremental map gate | **11/11 dialogues pass on the OnePlus and the host**, replayed one turn at a time (25 updates). Phone updates take 2.2–2.7 s before both people have spoken and 4.0–5.4 s (median 4.7 s) afterwards ([gate report](mediator-graph/device-gate.json)). See [Incremental conversation map](#incremental-conversation-map). |
| Phone analysis latency | **4.02–6.46 s**, median **4.91 s**, across ten synthetic dialogues on Gemma GPU after the latency follow-up; **10/10 automatic checks pass** and 6/10 are within five seconds ([gate report](mediator-latency/device-gate.json)). Original POC: 9.14–12.37 s, median 10.76 s. Live audio-end-to-card and rehearsal latency remain unmeasured. |
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

## Tabletop and latency follow-up

The tester reports that live speech and the growing conversation map work well. This is qualitative feedback, without a recorded latency or usefulness score. The live round was not saved: the installed original POC had no per-turn timing logger or conversation persistence. A read-only check found only the synthetic evaluation report on disk, and the retained app logcat snapshot yielded lifecycle messages rather than inference measurements. Updating/restarting the original app would lose its in-memory round.

The tabletop follow-up passes host APK/instrumentation builds, 27 unit tests, Android lint and the packaged offline-manifest check. It is now installed on the OnePlus together with the latency follow-up, but the tabletop UI has not been rehearsed on the phone; these checks do not establish on-device layout quality. Switching between tabletop and Debug uses the same session and does not reload the models.

## Latency investigation

Measured 2026-10-24 on the OnePlus CPH2655 (Android 16) through instrumentation, which runs the app process in Android's `foreground` scheduling group, not as the visible `top-app`. All inputs are synthetic; raw reports are in [`mediator-latency/`](mediator-latency/).

**Speech.** Six macOS-TTS clips (3.4–20.8 s) were streamed at microphone pace through Whisper Tiny English ([report](mediator-latency/asr-audio-context.json)):

| Encoder context | Done → final text | Partial arrival after its audio | Total compute / audio |
|---|---|---|---|
| 1,500 frames (previous default) | 485–678 ms | 567–671 ms | 0.23–0.57× |
| **500 frames (now bundled)** | **193–212 ms** | **221–362 ms** | **0.07–0.15×** |

Word error rate was equal or lower at 500 frames on every clip. On the 20-second clip it was 1.4% vs 5.6%, because the full context duplicated a phrase. Both settings misheard "four would" as "forward" in one clip. There were no repetition loops. The SDK's partial cadence (1.5, 3 and 5 s, then every 4.5 s) is fixed in Xybrid 0.13.1 and has no Kotlin setter; with a 10-second context, a five-second window costs about 150–200 ms here, so a shorter SDK cadence looks affordable. The Kotlin config exposes `audioCtx`, language, sample rate and VAD, but no chunk-size or thread setter. Static inspection of the pinned core found that this feed/flush route does not use VAD segmentation, so adding a Silero model is not an evidenced speedup.

**Gemma.** Each variant ran on a fresh engine over the ten dialogues ([report](mediator-latency/gemma-variants.json)). The original production configuration spent 1.26–1.39 s creating the conversation, about 0.53 s to the first token (~920 prompt tokens), then decoded 122–175 tokens at only **15.7–16.2 tok/s**.

| Variant (GPU unless noted) | Result |
|---|---|
| KV cache 4,096 instead of 8,192 | First token 0.1 s sooner; decode unchanged. Not adopted, to keep headroom for eight long turns. |
| No JSON constraint | 42.3–42.9 tok/s, but **0/10 valid**: early stops, wrong evidence shapes, unbalanced braces. |
| Speculative decoding (MTP) | Unconstrained 64.5–80.8 tok/s, 0/10 valid. With the constraint, garbled text or native `compute_mask() called after stop` errors. Requesting it once also left the drafter active for later conversations on that engine ([confounded run](mediator-latency/gemma-shared-engine-confounded.json)). Not usable with LiteRT-LM 0.18. |
| Prefill system prompt at conversation creation | First token unchanged (~0.37 s with 80 vs 920 prompt tokens); prefill is not the bottleneck. |
| CPU, four threads ([report](mediator-latency/gemma-cpu.json)) | Prefill 76–135 tok/s (7–12 s to first token); decode 11–17 tok/s, constrained and unconstrained alike, so the llguidance mask itself is cheap. 17–27 s per analysis. |

The constraint's cost is structural. For GPU logits, LiteRT-LM's `ConstrainedDecoder::ProcessLogits` copies the full-vocabulary logits to the CPU, masks them, and writes them back for every token: about 39 ms per token. About 70% of output tokens were JSON structure forced by the schema (`{"t":`, `"e":{"A":1,"B":2}`, newlines). The adopted changes remove those tokens rather than the constraint:

- Implied citations: plain claim text while each speaker has one turn; once anyone has several, every A/B claim is `{"t":…,"e":turn}`. A mixed format let E2B write a citation object inside a string on the host check, so the format is now uniform per output and labels cannot contain quotes or braces.
- Shared claims are plain text, cited by each speaker's own claim turn.
- `x-guidance: whitespace_flexible=false` removes the 10–17 newline tokens the grammar allowed.
- The next conversation is created in the background after each analysis, removing 1.3–2.5 s of setup from the result path.

Result through the production path ([gate report](mediator-latency/device-gate.json)): **52–91 output tokens, ~763 prompt tokens, no setup on the result path, 4.02–6.46 s per analysis (median 4.91 s), 10/10 automatic checks**. Decode is 14.3–15.3 tok/s. A recorded turn's measured path is now roughly 0.2 s of ASR finalization plus ~5 s of Gemma, against roughly 0.5 s plus ~11 s before. Live end-to-card timings still need a rehearsal.

Quality notes for the human review: the corrected request cites turn 3, unknown concerns stay empty, and no shared topic is labelled as common ground. Some questions still ask which need matters more ("What is the priority between…"), and one proposal leaked a speaker letter ("A: Propose a few alternative times"). The host check of the same prompt passes 10/10.

Sources: [Xybrid v0.13.1 streaming window selection](https://github.com/xybrid-ai/xybrid/blob/v0.13.1/crates/xybrid-core/src/streaming/session.rs#L539), [Whisper context guidance](https://github.com/xybrid-ai/xybrid/blob/v0.13.1/crates/xybrid-whisper/src/params.rs), [LiteRT-LM 0.18.0 constrained decoder](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/components/constrained_decoding/constrained_decoder.cc), [LiteRT-LM 0.18.0 benchmark metrics](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt#L674), [Gemma 4 E2B model card benchmarks](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).

## Incremental conversation map

Until this change every analysis re-read the whole transcript (at most eight turns) and replaced the map with a new snapshot. Now Gemma extends the map:

1. The input is the active map (one line per node: id, speaker, kind, label, links), the last two mapped turns for context, and only the unmapped turns. Prefill stays at about 1,000–1,100 tokens instead of growing with the transcript, so sessions allow 40 turns.
2. The output is additions only. For each speaker with new turns there is a request slot and a concern slot, plus an optional link from the request to an existing node: `replaces` (their own earlier request), `conflicts` or `supports` (the other speaker's claim). There is also an optional common ground and `intervene`, which is `none` or a reason (`impasse`, `misunderstanding`, `common_ground`, `next_step`) with a question and an optional next step. The schema allows only `none` until both have spoken.
3. The app validates each addition against the map and transcript, then applies it. Gemma never assigns node IDs. A concern explains its speaker's latest request. Common ground rests on each speaker's latest claim and replaces the previous one. Replaced nodes stay as history. Implausible links are dropped, while invalid claims fail the update as before.
4. Correcting a turn discards the nodes it produced, restores what they had replaced, and maps that turn again.

An open list of claims (`[{"k":"request","t":…}]`) let E2B drop B's explicit request on the host check, so each speaker uses fixed request/concern slots. Two prompt rules were tightened during the phone runs. "I need to check my schedule first" is now a request with an example; the concern definition's "constraint" had pulled it into the concern slot. Gemma also intervenes whenever requests differ, because one run returned `none` for an obvious conflict.

Phone result ([gate report](mediator-graph/device-gate.json)): all eleven dialogues, including the four-turn demo, pass the request, question, correction-citation and unknown-concern checks. First-turn updates decode 27–33 tokens in 2.2–2.7 s; later updates decode 53–70 tokens in 4.0–5.4 s.

The structure Gemma adds is still sparse. Across the eleven final graphs there are 4 `conflicts` links, 1 `replaces`, no `supports`, and no common ground in this run; the 13 `explains` links are inferred by the app. In the demo, A's "One reliable update" did not mark the earlier "Regular update" as replaced, so both stay active, and B's first request label reads as a concern. After both had spoken, Gemma chose `impasse` 13 times and `next_step` once. Proposals sometimes still contain a speaker letter ("A suggest a few alternative times"). These are the next quality targets, together with the human review.

### Map quality follow-up

The optional link was the weak point: constrained decoding mostly closed the object instead of adding it. Each request now carries two **required** decisions: `revises` (0 or one of that speaker's own request ids) and `vs` (`conflicts`, `supports` or `unrelated` with the other person's current request). The prompt now also contains:
- the people's names, so proposals stop saying "A suggest…";
- a rule against pronouns, because Gemma inferred one from a name;
- the latest suggested question, which Gemma may decline to repeat (the first update after both have spoken must still ask, which the schema enforces);
- a positive common-ground example;
- rules to ask both people and never rank their needs.

On the host check (same model, desktop CPU), model-emitted links rose from 6 to 13: conflicts 2 → 7, supports 3 → 4, replaces 1 → 2. The demo now marks "One reliable update" as replacing the earlier request, and all 11 dialogues still pass. Common ground remained rare (0 of 11). After the both-people rule, most host questions address both people; one still ranks their needs ("What is the priority between budget and equipment longevity?").

On the OnePlus ([gate report](mediator-graph/device-gate-quality.json)), all 11 dialogues pass. Model-emitted links rose from 5 to 13 (conflicts 4 → 7, supports 0 → 4, replaces 1 → 2), and the demo marks its revision and ends on "Does a short update at four work for both of you?". The extra decision tokens cost about 0.7 s per update: 2.3–2.6 s for the first turn and 4.4–6.0 s (median 5.4 s) afterwards. Common ground stayed at 0/11, and Gemma never declined to repeat a question. Some phone questions remained one-sided ("What does feeling like home mean to Sam in this context?") or ranked needs ("What matters most to each of you: training or resting…?").

An extra small model is not the fix for these failures. A classifier or embedding sees short labels without the conversation, while Gemma already has both and simply skipped optional fields. An embedding model would help later: picking the relevant nodes when long always-on sessions outgrow the prompt, merging near-duplicate claims, and detecting topic changes. Xybrid Kotlin already ships ONNX Runtime with `MeanPool`/`Normalize` steps, so a ~23 MB sentence encoder could run through the SDK. Speaker attribution would need a speaker-embedding model, a separate choice for the voice-detection stage.

### Common-ground follow-up

Inside the main update, Gemma left the optional `common` field empty in every final graph. Common ground is now a separate question asked in the **same** LiteRT-LM conversation, after the main update, whose prompt and turns are already prefilled. It runs after a new request while the map has no common ground, or when a new request supports the other person's. Its answer is decoded separately, and a failed or empty answer leaves the main update unchanged.

On the OnePlus ([gate report](mediator-graph/device-gate-common.json)) the check costs **0.44–1.0 s** (median about 0.6 s). With it, updates after both have spoken take 4.8–6.3 s. Four wordings were tried, all passing or failing the same 11-dialogue gate:

| Follow-up wording | Phone: dialogues with common ground | Wrong common ground | Gate |
|---|---|---|---|
| **Name briefly an outcome BOTH say they want; empty for a shared topic or opposite wants (kept)** | **7/11** ("Stay on schedule", "Timely delivery" in the demo, "A clear timeline") | "Weekend activity", "Update"; two compromises ("Affordable and durable equipment") | 11/11 |
| + phrase it as a goal starting with a verb + topic examples | 2/11 | none, but the real ones were lost | 11/11 |
| + verb phrase + "too/also" cue | 10/11 | topics turned into goals ("Spend the weekend", "Prefer a version") | fails unknown-concern |
| shared yes/no decision before the label | 0/11 | none | 11/11 |

The first wording is kept because it finds the demo's shared goal; its shared-topic false positives remain the main common-ground issue. A sturdier fix probably needs a precise definition of what each person said they want, for example the turn text quoted in the follow-up, or a human-labelled set of dialogues to tune against, rather than more prompt wording.

## Sources

- [LiteRT-LM Kotlin API, pinned v0.18.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md)
- [Gemma bundle, frozen revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/1caa527c1e88b55f046fcd8d2b4265606680663d)
- [Whisper Tiny English Q5_1, frozen revision](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1)
- [Xybrid Kotlin 0.13.1 Maven artifact](https://repo.maven.apache.org/maven2/ai/xybrid/xybrid-kotlin/0.13.1/)
- [ADK 1.4.0 / LiteRT-LM 0.18 compatibility issue](https://github.com/google/adk-kotlin/issues/738): ADK is outside this POC's dependency graph.
