# 免提语音: wake word and spoken replies

Off-device help does not apply here: everything in this feature runs on the phone.

## What it does

With **设置 → 手机 → 免提语音** on, the app keeps a foreground service listening for a wake
phrase (default "Hey DSH", changeable). Hearing it opens the quick panel and starts capture,
so a turn can begin with the phone in a pocket. Assistant replies are read aloud by the
on-device text-to-speech engine, sentence by sentence, starting before the turn finishes.
Saying the wake phrase while it is speaking stops the speech and starts capture (barge-in).

Audio is processed in memory and discarded. Nothing is recorded, saved, or uploaded.

## Architecture

```
WakeWordService (foreground, foregroundServiceType=microphone)
  └─ AudioRecord 16 kHz mono PCM16 -> sherpa-onnx KeywordSpotter
       └─ on detect: release the recorder, then QuickActivity + EXTRA_WAKE
            └─ QuickActivity starts the existing voice capture
                 └─ turn runs on the engine
                      └─ TalkbackService speaks the streamed prose
```

Two rules keep the audio handoff correct:

1. **The recorder is released before the activity is started.** Several devices refuse a
   second `AudioRecord` while one is open, and the capture path opens its own. The service
   calls `stop()`/`release()` synchronously, then posts the intent.
2. **The wake service never surfaces "listening" unless audio is actually running.** The
   status chip in settings reads `WakeWordService.status`; a denied microphone, a failed
   start or a missing model shows as blocked or unavailable instead of a green dot.

## The keyword line, and why it is computed rather than written

sherpa-onnx's keyword spotter matches *BPE pieces*, not words. A `keywords.txt` line is a
space-separated sequence of pieces from the model's vocabulary, optionally suffixed with
`#threshold` (trigger probability) and `:score` (boost).

The trap: sherpa's own loader only maps pieces to token ids. It does **not** tokenize text,
and a piece missing from `tokens.txt` is fatal — it logs and calls `SHERPA_ONNX_EXIT`, which
kills the process.

Appending a phrase such as `▁D` without checking produces exactly that. "Hey DSH" has no
`▁D` piece, so a hand-written line such as `▁HE Y ▁D S H` fails at load, and a plausible-
looking variant such as `▁HE Y ▁ D S H` loads but never fires because `▁` and `D` are
separate emissions.

So the phrase is tokenized the way the model would tokenize it:

- `assets/wake/vocab.tsv` holds the model's unigram piece table (piece TAB log-score),
  extracted once by `tools/gen-wake-vocab.py` from `bpe.model`. Shipping the text table
  means no protobuf parser runs in the app.
- `TextToBpe` reproduces sentencepiece's normalization (uppercase, collapse whitespace,
  dummy-prefix space) and picks the highest-scoring segmentation by dynamic programming,
  then validates every piece against `tokens.txt`.
- `TextToBpeGoldenTest` pins the result against encodings produced by real sentencepiece
  (fixture: `app/src/test/resources/wake/bpe-golden.json`). If the model or the table
  drifts, that test fails before a user ever hears nothing happen.

This is also why the wake phrase is free text: any phrase the model can express works, and
a phrase it cannot (for example CJK against an English vocabulary) is reported as
unsupported in settings rather than silently never firing.

## Model

`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (Apache-2.0), int8 weights, ~5.3 MB
in `assets/wake/`:

| File | Purpose |
|---|---|
| `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` | zipformer transducer |
| `tokens.txt` | piece to id map (what sherpa reads) |
| `vocab.tsv` | piece to log-score table (what the tokenizer reads) |
| `bpe.model` | the original tokenizer, kept for reproducibility; not loaded at runtime |

Model files are copied to `cacheDir` once, keyed by version code, because the keyword file
must be a real file path. The runtime comes from the vendored `sherpa-onnx` AAR in
`app/libs`, trimmed to arm64-v8a.

## Settings and persistence

The section lives on the existing **手机** settings page; it is not a new destination.
All state is a single string map in the datastore that already holds `floating_ball` and
`theme` (`SettingsRepository.voice`), so there is no parallel config file:

`wake_enabled`, `wake_listening`, `wake_phrase`, `wake_sensitivity`,
`talkback_enabled`, `talkback_voice`, `talkback_rate`, `talkback_pitch`,
`talkback_with_screen_reader`, `offline_dictation`

The services re-read settings on every start; nothing survives in memory across a process
death.

## Screen readers

Google TalkBack already reads the screen. To avoid speaking twice, talkback stays silent
while a screen reader is active unless the user turns on the explicit override. Only
`FEEDBACK_SPOKEN` accessibility services from *other* packages count — DSH's own
accessibility service (which the app asks users to enable for on-screen actions) must never
suppress spoken replies.

## Speaking granularity: what the engine actually exposes

The brief asks for speech to start "before the turn finishes". What the engine gives us
matters here, and it is less than per-token streaming.

`@deepseek-ai/dsh-sdk-jsonrpc-server` emits exactly three notifications: `session.event`,
`session.status`, `subagent.started`/`subagent.finished`. There is no per-delta event.
`session.event` carries `assistant/message`, which `dsh-agent-loop` appends *once per completed
assistant message* (`live.settle("assistant/message", …)`) — the token stream lives inside that
event's `stream` array, but it arrives in one piece rather than growing a delta at a time.

So the honest behaviour is:

- A reply is queued when each assistant message lands, not while the model is generating.
- The sentence pipeline still splits that text and enqueues each sentence with `QUEUE_ADD`, so
  playback begins as soon as the first message exists and long replies are spoken
  progressively rather than after a full synthesise-the-whole-thing delay.
- Within a multi-step turn (tool call, then another message) speech follows each message.

Starting mid-generation would need a per-delta seam inside the engine's SDK server, which no
current notification provides. The delivery pipeline is written against `SpeechAction` and does
not assume message granularity, so narrowing it later is a seam change, not a rewrite.

## Known limitations

- **No boot restore on Android 14+.** A microphone-type foreground service cannot be
  started from `BOOT_COMPLETED`; the platform refuses it. With the toggle on, the service
  resumes the next time the app is opened (and on API 29–33 it does start at boot). The
  settings copy says so rather than promising otherwise.
- **False-trigger rate is untuned on real audio.** The sensitivity slider maps to the
  spotter's trigger threshold, and the default sits mid-range. The < 1/hour target in the
  brief needs a measured session on a real phone; see the verification table in the PR.
- **Latency figures are unmeasured here.** The < 1.5 s dictation and < 1.0 s first-word
  targets are device measurements; no Android hardware was available to this build.
