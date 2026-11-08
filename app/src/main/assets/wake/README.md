# Vendored: wake-word model

`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` — an open-vocabulary keyword-spotting
zipformer from the sherpa-onnx project.

- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2>
- Upstream archive: 17,626,723 bytes
- License: **Apache-2.0** (declared in the model's own README `license:` field; upstream ships
  no separate LICENSE file, so this note is the attribution)
- Training data: GigaSpeech XL (~10,000 h), modelling unit BPE, ~3.3 M parameters

## Files shipped and why

| File | Size | Role |
|---|---|---|
| `encoder.int8.onnx` | 4.8 MB | zipformer encoder (int8 weights) |
| `decoder.int8.onnx` | 272 KB | transducer decoder |
| `joiner.int8.onnx` | 160 KB | transducer joiner |
| `tokens.txt` | 5.0 KB | piece → id, read by sherpa |
| `vocab.tsv` | 9.0 KB | piece → log-score, read by `TextToBpe` |
| `bpe.model` | 245 KB | the original tokenizer, kept for reproducibility; **not loaded at runtime** |

Total ≈ 5.3 MB. The fp32 weights are not shipped: int8 recognises the same phrases at a third
of the size, which matters for an APK.

`vocab.tsv` is generated, not hand-written:

```sh
python3 tools/gen-wake-vocab.py <model-dir> app/src/main/assets/wake/vocab.tsv
```

It exists so the app never parses a possibly-untrusted protobuf at runtime; `TextToBpe` reads
plain text. `TextToBpeGoldenTest` verifies the table against `bpe.model`, and verifies the
tokenizer's output against real sentencepiece, so the two cannot drift apart silently.

## Reproducing

```sh
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
tar -xjf sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
cd sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
cp encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx <assets>/wake/encoder.int8.onnx
cp decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx <assets>/wake/decoder.int8.onnx
cp joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx  <assets>/wake/joiner.int8.onnx
cp tokens.txt bpe.model                              <assets>/wake/
python3 tools/gen-wake-vocab.py . <assets>/wake/vocab.tsv
```

## A note on the vocabulary

This is an **English** BPE model with a 500-piece vocabulary of upper-case pieces. A wake
phrase in another script (for example CJK) has no segmentation here, so `TextToBpe` reports it
as unsupported and the settings screen says so instead of listening forever for something it
can never hear. Supporting other languages means shipping a different KWS model, not retraining
this one.
