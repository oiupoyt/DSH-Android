# 免提语音: offline wake word + spoken replies

> Fork of `jackwener/DSH-Android` (`io.github.jackwener.dsh`) adding an opt-in, fully on-device
> hands-free voice loop. Everything below was verified in this working tree; a section at the end
> lists what could **not** be verified without a physical phone, and it is not padded.

## What this adds

1. **Wake word** — a foreground service listens for a user-chosen phrase (default "Hey DSH"),
   releases the microphone, and opens the quick panel already capturing.
2. **Talkback** — assistant replies are read aloud by the phone's own TTS engine, sentence by
   sentence, with barge-in.
3. **One settings surface** — a single 免提语音 section on the existing 设置 → 手机 page.

Off by default. The existing push-to-talk microphone is untouched and still works.

---

## Three premises in the brief were false. Here is the evidence, and what I did instead.

These are not opinions; each was checked against the actual artifacts.

### 1. "The app already ships the sherpa-onnx runtime for SenseVoice — reuse it"

**False.** The engine tree prunes it:

```text
js/build-engine.sh:
  for d in "$NM"/sherpa-onnx*; do [[ -e "$d" ]] && rm -rf "$d"; done
js/README.md:
  Prune: … `sherpa-onnx*`, the web client UI and inspector, …
js/engine/package-lock.json: 21 "sherpa" entries — all of them pruned at packaging time
```

There is also no Android artifact to depend on: `com.k2fsa.sherpa.onnx` returns `numFound: 0`
from Maven Central, and `sherpa-onnx-node@1.13.8` lists only win/linux/darwin optional natives —
no Android. So "reuse the bundled runtime, no size increase" was never available.

**Chosen instead:** the upstream official **sherpa-onnx AAR v2.0.1**, trimmed to arm64-v8a and
vendored at `app/libs/sherpa-onnx-2.0.1-arm64.aar` (12.5 MB, was 49.4 MB). No new dependency
resolver, no network at build time. Provenance and the exact trim script:
`app/libs/README.md`.

**Why not Porcupine / openWakeWord** (the brief's fallbacks): Porcupine needs a licence key and a
per-device activation flow, which contradicts "no analytics, no cloud, works offline from first
launch". openWakeWord would mean adding onnxruntime + a second model format + custom mel/embedding
glue, i.e. more code than the vendored AAR for no accuracy gain at this scale. sherpa-onnx's
`KeywordSpotter` was already the right primitive and its symbols **are** exported — verified:

```text
$ readelf/strings on jni/arm64-v8a/libsherpa-onnx-jni.so
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromFile
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_createStream
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_decode
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_getResult
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_reset
```

### 2. "Build the `keywords.txt` line for 'hey dsh' from the token spellings" — the suggested spelling does not work

The brief's example suffix was `▁HE`, `Y`, `▁D`, `S`, `H`. Two of those do not behave as assumed,
and this is the difference between "wrong" and "silently never fires":

```text
$ grep -c '▁D' tokens.txt          # nowhere in the 500-piece vocabulary
0

$ python3 -c "sentencepiece ... get_piece_size"
'HEY DSH' -> ['▁HE', 'Y', '▁', 'D', 'SH']
```

So "Hey DSH" tokenizes with a **bare space marker** `▁`, then `D`, then `SH`. A hand-written
`▁HE Y ▁D S H` line makes sherpa log and **exit the process** (`SHERPA_ONNX_EXIT` in `EncodeBase`),
and the plausible-looking `▁HE Y ▁ D S H` loads fine and then never fires, because `▁` and `D` are
separate acoustic emissions.

Because the brief also asks for a **user-changeable** phrase, the line cannot be written by hand at
all. It is computed:

- `tools/gen-wake-vocab.py` extracts the model's unigram piece table into
  `assets/wake/vocab.tsv` (plain TSV, so **no protobuf parser ships in the app**).
- `TextToBpe` reproduces sentencepiece normalization (uppercase, collapse whitespace,
  dummy-prefix space) and finds the highest-scoring segmentation by dynamic programming, then
  validates every piece against `tokens.txt` **before** sherpa is constructed.
- `TextToBpeGoldenTest` pins 24 phrases — including "HEY DSH" — against encodings produced by real
  sentencepiece, so the encoder cannot silently drift from the model.

Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (Apache-2.0), int8, ~5.3 MB in
assets. Provenance: `app/src/main/assets/wake/README.md`.

I also could not confirm the brief's claim that a KWS model triggers on synthesized speech: the
piper-synthesised "Hey DSH" did **not** trigger, while the model's own real-human test audio did
(`LIGHT UP` → detected, `THE YELLOW LAMPS` → detected, at the default threshold). That is a
sample-rate/prosody artefact of TTS, not a broken pipeline, but it means **the false-positive and
detection rates must be tuned on real speech, which I could not do here.**

### 3. Part 3 — "enable `@deepseek-ai/dsh-experimental-voice-input-bundle` in the profile" — would not deliver offline transcription

The bundle's own `cordis.patch.yml` inserts `speech-to-text-sensevoice`, which is
`dependencies: { "sherpa-onnx-node": "1.13.8" }`. That package has **no Android native build**
(optional deps are win-x64/win-arm64/win-ia32/linux-x64/linux-arm64/darwin-x64/darwin-arm64), and
the engine packaging deletes every `sherpa-onnx*` directory. On a phone the provider therefore has
no runtime. `@deepseek-ai/dsh-experimental-client-ui-voice-input` is additionally a **browser**
plugin (`lib/client.js` calls `window.__ModuleLoader__.load`), and it also downloads ~239 MB of
SenseVoice weights on first use — which contradicts both "offline from first launch" and "no
download".

So the profile was **not** changed. Instead transcription is native, using the same vendored AAR:

- Model: `sherpa-onnx-streaming-zipformer-en-2023-06-26` int8 (~72 MB in `assets/asr/`).
- Verified offline before writing any Kotlin — this exact file set, transcribed with
  sherpa-onnx 2.0.1:

  ```text
  prompt:     "Open the settings page and check the weather."
  transcript: OPENED THE SETTINGS PAGE AND CHECK O WEATHER
  ```

That is the honest cost: **~77 MB added to the APK** (5.3 MB wake + 72 MB ASR) against the
brief's assumed zero. It is the price of the "no cloud STT" requirement actually holding in
airplane mode. If that size is unacceptable, the ASR model is a single directory and can be made a
one-time in-app download instead — but then the "offline from first launch" promise weakens.

---

## Design

```text
WakeWordService (foreground, foregroundServiceType=microphone)
  └─ AudioRecord 16 kHz mono PCM16 → sherpa-onnx KeywordSpotter
       └─ detect: release the recorder → TalkbackService.stopSpeaking (barge-in)
            └─ QuickActivity + EXTRA_WAKE  → auto-starts capture
                 └─ turn runs on the engine
                      └─ TalkbackService speaks the prose, sentence by sentence
```

Two invariants are enforced in code and covered by review of the call order:

- **The recorder is closed before the activity starts** (`WakeWordService.handoff()` calls
  `closeAudio()` first). Two `AudioRecord`s on one device fight over the microphone.
- **"Listening" is only ever reported while a recorder is actually recording.** A denied
  permission, a failed start, or a missing model renders as blocked/unavailable, never as a
  green dot.

### Barge-in

Detecting the phrase calls `TalkbackService.stopSpeaking()` before launching the panel. A new user
turn and the toggle going off do the same.

### Not double-speaking with a screen reader

Google TalkBack already reads the screen, so spoken replies stay silent while it is active,
unless the user enables the explicit override (default off). The check is deliberately **not**
`AccessibilityManager.isEnabled`, because that is true whenever *any* accessibility service runs —
including DSH's own `DshAccessibilityService`, which the app asks users to enable. Using it would
have silently disabled spoken replies for exactly the users most likely to be hands-free. Instead
only `FEEDBACK_SPOKEN` services **from another package** count.

### Settings and persistence

One section, on the existing 手机 page. All state is a single string map in the datastore that
already holds `floating_ball` and `theme` — no parallel config file:

```text
wake_enabled, wake_listening, wake_phrase, wake_sensitivity,
talkback_enabled, talkback_voice, talkback_rate, talkback_pitch,
talkback_with_screen_reader, offline_dictation
```

The master switch gates both services; turning it off stops the service and shuts the TTS engine
down in the same call. Rows stay visible but disabled while it is off, so the cost is visible
before opting in. Every service re-reads settings on start rather than trusting memory.

---

## Verification

### Green, and reproducible

```text
$ ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -Pdsh.fetchShizuku=false
BUILD SUCCESSFUL

TOTAL tests=514 failures=0 errors=0 skipped=0
```

New tests, all passing (82 of the 514):

| Test | N | Covers |
|---|---|---|
| `TextToBpeGoldenTest` | 8 | tokenizer reproduces real sentencepiece; table matches `tokens.txt`; arbitrary phrases |
| `VoiceSettingsTest` | 11 | defaults off, clamping, sensitivity→threshold monotonicity, suppression rules |
| `VoicePersistenceTest` | 5 | datastore round-trip, corrupt blob, partial blob, pinned key names |
| `WakeGateTest` | 9 | 1500 ms debounce boundary, backoff ladder, run gating |
| `SpeechTextTest` | 23 | markdown→prose, code fences, JSON detection, sentence boundaries, abbreviations, chunking |
| `SpeakerStateTest` | 14 | screen-reader suppression, skip-on-data, no-repeat-on-complete, tail flush |
| `OfflineDictationTest` | 12 | PCM16 endianness/extremes, partial dedupe, endpoint finalisation |

Red/green honesty: the pure layers were written test-first and seen to fail first. Two exceptions,
stated rather than glossed:

- The very first red run never reached the compiler — the tree was broken by an unescaped
  apostrophe in a string resource and a malformed `try` I had introduced. The red was demonstrated
  afterwards by removing the declarations, and is labelled as such.
- The Android surfaces (`TalkbackService` engine lifecycle, audio focus, notifications, locale
  refusal) have **no** failing-first run because they are not JVM-testable. They compile and are
  reviewed; they are not proven.

### Claims I checked by experiment, not by reading

| Claim | Method | Result |
|---|---|---|
| KWS symbols exist in the shipped AAR | `strings`/`javap` on the trimmed AAR | present |
| `▁D` is absent from the vocabulary | parse `tokens.txt` | absent — the brief's spelling breaks |
| guessed line hard-fails | sherpa `KeywordSpotter` load | `Cannot find ID for token ▁D` → exit |
| correct line works on real speech | model's own test wavs | `LIGHT UP`, `THE YELLOW LAMPS` detected |
| tokenizer is reproducible in Kotlin | 24 phrases vs sentencepiece | exact match |
| ASR model works offline | sherpa-onnx 2.0.1 on the shipped files | transcript produced |
| reply streaming granularity | inspect `dsh-agent-loop` + `dsh-sdk-jsonrpc-server` | one `assistant/message` per completed message; **no** per-token notification exists |
| no network in the new code | grep for HTTP/socket/download in `wake/` | none |
| no audio written to disk | grep for audio file writes in `wake/` | none; only model/keyword text is cached |

### Tests from the brief that were **not** run, and why

No Android hardware or emulator was available in this environment (SDK installed, `adb devices`
empty, no system image). These are therefore **unverified**, not "passing":

| # | Test | Status |
|---|---|---|
| 1 | Detection → capture → draft | unverified (needs device mic) |
| 2 | Reply spoken sentence-by-sentence | unverified (needs TTS engine) |
| 3 | Barge-in | unverified (logic in place, not observed) |
| 4 | False positives over 30 min, target < 1/hour | **unverified — cannot be tuned without real speech** |
| 5 | Audio handoff, cold and warm start | order enforced in code; unverified on hardware |
| 6 | Airplane mode end-to-end | model verified offline in isolation; the full loop is untested |
| 7 | Battery mAh over 1 h | unverified |
| 8 | Doze / OEM kill, 8 h | unverified |
| 9 | Call/music conflict, ducking | unverified |
| 10 | Permission denial | logic + UI states implemented, unverified on device |
| 11 | Screen reader, no double-speak | rule unit-tested, unverified with real TalkBack |
| 12 | Boot restore | **partly impossible on Android 14+** — see below |
| 13 | Settings persistence across process kill | datastore round-trip unit-tested; kill/restore unverified |

### Known limitation found during review: no boot restore on Android 14+

A `microphone`-type foreground service cannot be started from `BOOT_COMPLETED` on Android 14+; the
platform refuses it. The service reports `Off` silently in that case rather than lying, and
`DshApp` restores it the next time the app opens. On API 29–33 the boot path works. The settings
copy says "resume after a restart (open the app once on some systems)" instead of promising
otherwise.

---

## Files

New, under `app/src/main/java/io/github/jackwener/dsh/wake/`:

| File | Role |
|---|---|
| `TextToBpe.kt` | phrase → validated sherpa keyword line (unigram segmentation) |
| `VoiceSettings.kt` | state, defaults, clamping, sensitivity→threshold |
| `KeywordSpotter.kt` | KWS runtime wrapper; asset→cache copy; typed failures |
| `WakeWordService.kt` | foreground service: mic, backoff, debounce, handoff, 5 s phrase test |
| `SpeechText.kt` | markdown→prose, sentence splitting, `SpeakerState` |
| `TalkbackService.kt` | TTS engine, queueing, barge-in, locale refusal, voice enumeration |
| `VoiceSettingsScreen.kt` | the 免提语音 section (+ `SettingsSlider`, the design system's first) |
| `OfflineSpeechRecognizer.kt`, `OfflineDictation.kt` | native offline ASR (see Part 3 above) |

Assets and build:

- `app/libs/sherpa-onnx-2.0.1-arm64.aar` + `README.md` (provenance, trim script)
- `app/src/main/assets/wake/` (5.3 MB KWS model, int8) + `README.md`
- `app/src/main/assets/asr/` (72 MB ASR model, int8)
- `tools/gen-wake-vocab.py` (regenerates `vocab.tsv` from `bpe.model`)

Modified, tightly scoped (217 insertions across 11 files, no reformatting of untouched code):

| File | Change |
|---|---|
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_MICROPHONE`, two `<service>` entries |
| `core/data/SettingsRepository.kt` | `voice` blob + `setVoice` (the existing datastore) |
| `core/session/SessionStore.kt` | hands raw events to talkback before `slim()` strips the stream |
| `core/trigger/TriggerReceiver.kt` | boot restore attempt |
| `DshApp.kt` | app-start restore |
| `feature/quick/QuickActivity.kt` | `EXTRA_WAKE` handling |
| `feature/conversation/ConversationViewModel.kt` | active session for spoken replies |
| `feature/settings/{SettingsScreen,SettingsViewModel}.kt` | mount the section, `updateVoice` |
| `res/values{,-en}/strings.xml` | the section's copy, additive only |
| `app/build.gradle.kts` | one `implementation(files(...))` line |
| `.gitignore` | `.kotlin/` |

---

## Honest summary

The parts that are provable here are the ones that decide whether this feature *can* work: the
tokenizer is exactly right, the keyword line cannot crash sherpa, the model files are real and
transcribe offline, the audio handoff order is correct, and none of it talks to a network. The
parts that need a phone — detection rate, latency, battery, identifying and tuning the threshold
against real voices — are called out as unverified instead of asserted.

Two deviations from the brief are significant and deliberate: **~77 MB was added to the APK**
(reuse was impossible) and **Part 3's profile change was not made** (it cannot work on Android).
Both are argued above with the commands that show it.
