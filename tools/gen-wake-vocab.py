#!/usr/bin/env python3
"""Generate the wake-word tokenizer table from a sherpa-onnx KWS model.

The keyword spotter's `keywords.txt` lines are space-separated BPE pieces. sherpa's own
loader only maps pieces to ids through `tokens.txt`; it never tokenizes text, and an
unknown piece makes it exit the process. So DSH-Android tokenizes a user's wake phrase on
device, using the scores of the model's `bpe.model` (a SentencePiece unigram model).

Parsing that protobuf at runtime is avoidable: this script extracts the
(piece, log-score) table once and writes it as a plain TSV next to the model, so the app
only reads text. Re-run it whenever the bundled model changes.

    python3 tools/gen-wake-vocab.py <model-dir> <out.tsv>

Requires `sentencepiece` (pip install sentencepiece).
"""
import sys
from pathlib import Path

from sentencepiece import sentencepiece_model_pb2 as pb

TYPE_NORMAL = 1


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    model_dir, out = Path(sys.argv[1]), Path(sys.argv[2])
    proto = pb.ModelProto()
    proto.ParseFromString((model_dir / "bpe.model").read_bytes())

    rows = [(p.piece, p.score) for p in proto.pieces if p.type == TYPE_NORMAL]
    if not rows:
        print("no normal pieces in bpe.model", file=sys.stderr)
        return 1

    # Pieces are written verbatim; a TAB separates piece from score.
    out.write_text(
        "".join(f"{piece}\t{score:.9g}\n" for piece, score in rows),
        encoding="utf-8",
    )
    print(f"{out}: {len(rows)} pieces")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
