"""Translate one bounded batch using local Argos model files."""

import json
import re
import sys
from pathlib import Path

import ctranslate2
import sentencepiece


def translate(model_dir, texts):
    model = Path(model_dir)
    tokenizer = sentencepiece.SentencePieceProcessor(model_file=str(model / "sentencepiece.model"))
    translator = ctranslate2.Translator(str(model / "model"), device="cpu", compute_type="int8", intra_threads=1)
    sentences = [re.split(r"(?<=[.!?])\s+", text.strip()) for text in texts]
    tokens = tokenizer.encode([sentence for text in sentences for sentence in text], out_type=str)
    output = iter(translator.translate_batch(tokens, beam_size=2, max_input_length=512,
                                            max_decoding_length=384, replace_unknowns=True))
    translated = [" ".join(tokenizer.decode(next(output).hypotheses[0]) for _ in text) for text in sentences]
    return [" ".join(text.replace("▁", " ").split()) for text in translated]


def main():
    model_root, target = sys.argv[1:]
    if target not in {"zh", "ja", "ko", "fr", "de", "es"}:
        raise ValueError("Unsupported translation target")
    texts = json.load(sys.stdin)
    if not isinstance(texts, list) or not 1 <= len(texts) <= 2 or any(
        not isinstance(text, str) or not text.strip() or len(text) > 512 for text in texts
    ):
        raise ValueError("Invalid translation batch")
    print(json.dumps(translate(Path(model_root) / target, texts), ensure_ascii=False))


if __name__ == "__main__":
    main()
